package com.rigour.analytics.infrastructure.persistence.scope;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 看板商品预计算：整批替换，历史修正/删除也重算；失败保留上一个完整版本。 */
@Component
public class BiDashboardProductProjector {
    private final JdbcTemplate jdbc;

    public BiDashboardProductProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全量源镜像成功后退役已物理删除的事实，不能仅依赖更新时间水位。 */
    @Transactional
    public void retireMissingFacts(String tenant) {
        for (String[] mapping :
                new String[][] {
                    {"bi_customer_dim", "customer_id", "bi_source_crm_crm_customer"},
                    {"bi_sales_order_fact", "order_id", "bi_source_order_order_sales_order"},
                    {
                        "bi_sales_order_line_fact",
                        "order_line_id",
                        "bi_source_order_order_sales_order_line"
                    },
                    {"bi_sales_payment_fact", "payment_id", "bi_source_order_order_payment_record"}
                }) {
            jdbc.update(
                    "UPDATE "
                            + mapping[0]
                            + " f SET deleted=1 WHERE tenant_id=? AND deleted=0"
                            + " AND NOT EXISTS(SELECT 1 FROM "
                            + mapping[2]
                            + " s WHERE s.tenant_id=f.tenant_id AND s.id=f."
                            + mapping[1]
                            + ")",
                    tenant);
        }
    }

    @Transactional
    public long refresh(String tenant, long runId, Instant syncedAt) {
        var at = LocalDateTime.ofInstant(syncedAt, ZoneOffset.UTC);
        jdbc.update("DELETE FROM bi_dashboard_customer_history WHERE tenant_id=?", tenant);
        jdbc.update(
                """
INSERT INTO bi_dashboard_customer_history(tenant_id,customer_id,first_order_date,synced_time)
SELECT tenant_id,customer_id,MIN(order_date),? FROM bi_sales_order_fact
WHERE tenant_id=? AND customer_id IS NOT NULL AND deleted=0
  AND COALESCE(order_status_code,'')<>'CANCELLED' GROUP BY tenant_id,customer_id
""",
                at,
                tenant);
        jdbc.update("DELETE FROM bi_dashboard_payment_product WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM bi_dashboard_order_product WHERE tenant_id=?", tenant);
        // 按订货金额比例分摊整单应收/累计已收，避免将外部行金额误认为整单折后金额。
        // 最后一行吸收六位小数尾差，保证每张订单的金额精确守恒。
        int orders =
                jdbc.update(
                        """
INSERT INTO bi_dashboard_order_product
  (tenant_id,order_id,order_line_id,product_id,product_variant_id,product_category_id,
   product_name,product_category_name,sku_code,specification_snapshot,
   allocation_status,allocation_weight,sales_amount,cohort_paid_amount,synced_time)
WITH source_lines AS (
  SELECT l.*, SUM(unit_price*quantity) OVER(PARTITION BY tenant_id,order_id) gross,
    MIN(unit_price*quantity) OVER(PARTITION BY tenant_id,order_id) minimum_amount,
    ROW_NUMBER() OVER(PARTITION BY tenant_id,order_id ORDER BY order_line_id DESC) last_line
  FROM bi_sales_order_line_fact l WHERE tenant_id=? AND deleted=0
), amounts AS (
  SELECT o.tenant_id,o.order_id,l.order_line_id,l.product_id,l.product_variant_id,
    l.product_category_id,l.product_name,l.product_category_name,l.sku_code,
    l.specification_snapshot,l.last_line,o.payable_amount,o.paid_amount,
    l.unit_price*l.quantity/l.gross weight,
    ROUND(o.payable_amount*l.unit_price*l.quantity/l.gross,6) sales,
    ROUND(o.paid_amount*l.unit_price*l.quantity/l.gross,6) paid
  FROM bi_sales_order_fact o JOIN source_lines l ON l.tenant_id=o.tenant_id AND l.order_id=o.order_id
  WHERE o.tenant_id=? AND o.deleted=0 AND COALESCE(o.order_status_code,'')<>'CANCELLED'
    AND l.gross>0 AND l.minimum_amount>=0
)
SELECT tenant_id,order_id,order_line_id,product_id,product_variant_id,product_category_id,
  product_name,product_category_name,sku_code,specification_snapshot,'ALLOCATED',weight,
  sales+CASE WHEN last_line=1 THEN payable_amount-SUM(sales) OVER(PARTITION BY tenant_id,order_id) ELSE 0 END,
  paid+CASE WHEN last_line=1 THEN paid_amount-SUM(paid) OVER(PARTITION BY tenant_id,order_id) ELSE 0 END,?
FROM amounts
""",
                        tenant,
                        tenant,
                        at);
        orders +=
                jdbc.update(
                        """
INSERT INTO bi_dashboard_order_product
  (tenant_id,order_id,order_line_id,allocation_status,sales_amount,cohort_paid_amount,synced_time)
SELECT o.tenant_id,o.order_id,0,'UNALLOCATABLE',o.payable_amount,o.paid_amount,?
FROM bi_sales_order_fact o WHERE o.tenant_id=? AND o.deleted=0
  AND COALESCE(o.order_status_code,'')<>'CANCELLED'
  AND NOT EXISTS(SELECT 1 FROM bi_dashboard_order_product a
    WHERE a.tenant_id=o.tenant_id AND a.order_id=o.order_id)
""",
                        at,
                        tenant);
        int payments =
                jdbc.update(
                        """
INSERT INTO bi_dashboard_payment_product
  (tenant_id,payment_id,order_id,order_line_id,payment_time,allocated_amount,allocation_status,synced_time)
WITH amounts AS (
  SELECT p.tenant_id,p.payment_id,p.order_id,p.payment_time,p.paid_amount,
    a.order_line_id,ROUND(p.paid_amount*a.allocation_weight,6) amount,
    ROW_NUMBER() OVER(PARTITION BY p.tenant_id,p.payment_id ORDER BY a.order_line_id DESC) last_line
  FROM bi_sales_payment_fact p JOIN bi_dashboard_order_product a
    ON a.tenant_id=p.tenant_id AND a.order_id=p.order_id AND a.allocation_status='ALLOCATED'
  WHERE p.tenant_id=? AND p.deleted=0
)
SELECT tenant_id,payment_id,order_id,order_line_id,payment_time,
  amount+CASE WHEN last_line=1 THEN paid_amount-SUM(amount) OVER(PARTITION BY tenant_id,payment_id) ELSE 0 END,
  'ALLOCATED',? FROM amounts
""",
                        tenant,
                        at);
        // 无明细、无订单关联或无有效分母仍保留金额，不能静默丢弃或伪装为商品回款零。
        payments +=
                jdbc.update(
                        """
INSERT INTO bi_dashboard_payment_product
  (tenant_id,payment_id,order_id,order_line_id,payment_time,allocated_amount,allocation_status,synced_time)
SELECT p.tenant_id,p.payment_id,p.order_id,0,p.payment_time,p.paid_amount,'UNALLOCATABLE',?
FROM bi_sales_payment_fact p WHERE p.tenant_id=? AND p.deleted=0
  AND NOT EXISTS(SELECT 1 FROM bi_dashboard_payment_product a
    WHERE a.tenant_id=p.tenant_id AND a.payment_id=p.payment_id)
""",
                        at,
                        tenant);
        jdbc.update("DELETE FROM bi_dashboard_product_snapshot WHERE tenant_id=?", tenant);
        jdbc.update(
                """
INSERT INTO bi_dashboard_product_snapshot
  (tenant_id,run_id,synced_time,order_product_count,payment_product_count) VALUES(?,?,?,?,?)
""",
                tenant,
                runId,
                at,
                orders,
                payments);
        return (long) orders + payments;
    }
}
