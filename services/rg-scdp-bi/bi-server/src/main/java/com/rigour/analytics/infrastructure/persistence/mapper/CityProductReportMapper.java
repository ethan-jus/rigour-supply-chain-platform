package com.rigour.analytics.infrastructure.persistence.mapper;

import com.rigour.analytics.application.port.out.CityProductReportStore.FactRow;
import com.rigour.analytics.api.v1.model.CityProductReportView.CustomerArchive;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 仅查询 BI 本地事实表；不联付款流水，避免一单多次付款放大金额。 */
@Mapper
public interface CityProductReportMapper {
    @Select("SELECT descendant_id FROM bi_product_category_closure WHERE tenant_id = #{tenantId} AND ancestor_id = #{parentId}")
    List<Long> categoryIds(@Param("tenantId") String tenantId, @Param("parentId") Long parentId);
    @Select("""
            <script>
            SELECT region_code AS regionCode, MAX(region_name) AS regionName,
                   COUNT(DISTINCT customer_id) AS customerCount
              FROM bi_customer_dim
             WHERE tenant_id = #{tenantId} AND deleted = 0
            <if test="regionCode != null">AND region_code = #{regionCode}</if>
            <if test="ownerStaffCode != null">AND owner_staff_code = #{ownerStaffCode}</if>
            <if test="customerTypeCode != null">AND customer_type_code = #{customerTypeCode}</if>
             GROUP BY region_code ORDER BY region_code
            </script>
            """)
    List<CustomerArchive> customerArchives(@Param("tenantId") String tenantId,
            @Param("regionCode") String regionCode, @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode);

    @Select("""
            SELECT MAX(order_date) FROM bi_sales_order_fact
             WHERE tenant_id = #{tenantId} AND deleted = 0 AND order_status_code != 'CANCELLED'
            """)
    LocalDateTime latestOrderDate(@Param("tenantId") String tenantId);

    @Select("""
            <script>
            WITH scoped_orders AS (
                SELECT o.* FROM bi_sales_order_fact o
                 WHERE o.tenant_id = #{tenantId} AND o.deleted = 0
                   AND o.order_status_code != 'CANCELLED'
                   AND o.order_date &gt;= #{from} AND o.order_date &lt;= #{to}
                <if test="regionCode != null">AND o.region_code = #{regionCode}</if>
                <if test="ownerStaffCode != null">AND o.owner_staff_code = #{ownerStaffCode}</if>
                <if test="customerTypeCode != null">AND o.customer_type_code = #{customerTypeCode}</if>
                <if test="sourceSystemCode != null">AND o.source_system_code = #{sourceSystemCode}</if>
                <if test="productCategoryId != null or brandId != null or productId != null or skuId != null">
                   AND EXISTS (
                       SELECT 1 FROM bi_sales_order_line_fact selected_line
                         LEFT JOIN bi_product_dim selected_product ON selected_product.tenant_id = selected_line.tenant_id AND selected_product.product_id = selected_line.product_id
                        WHERE selected_line.tenant_id = o.tenant_id AND selected_line.order_id = o.order_id
                          AND selected_line.deleted = 0
                        <if test="productCategoryId != null">AND (CASE WHEN selected_product.product_id IS NOT NULL THEN selected_product.product_category_id ELSE selected_line.product_category_id END) IN (SELECT descendant_id FROM bi_product_category_closure WHERE tenant_id = #{tenantId} AND ancestor_id = #{productCategoryId})</if>
                        <if test="brandId != null">AND (CASE WHEN selected_product.product_id IS NOT NULL THEN selected_product.brand_id ELSE selected_line.brand_id END) = #{brandId}</if>
                        <if test="productId != null">AND selected_line.product_id = #{productId}</if>
                        <if test="skuId != null">AND selected_line.product_variant_id = #{skuId}</if>
                   )
                </if>
                 ORDER BY o.order_date, o.order_id
                 LIMIT #{orderLimit}
            ), line_totals AS (
                SELECT l.tenant_id, l.order_id, COUNT(*) AS line_count, SUM(l.line_amount) AS line_total
                  FROM bi_sales_order_line_fact l
                  JOIN scoped_orders o ON o.tenant_id = l.tenant_id AND o.order_id = l.order_id
                 WHERE l.tenant_id = #{tenantId} AND l.deleted = 0
                 GROUP BY l.tenant_id, l.order_id
            )
            SELECT o.order_id AS orderId, o.order_no AS orderNo, o.source_order_no AS sourceOrderNo,
                   o.source_system_code AS sourceSystemCode, o.customer_id AS customerId,
                   o.region_code AS regionCode, o.region_name AS regionName, o.owner_staff_code AS ownerStaffCode,
                   o.order_date AS orderDate, o.payable_amount AS payable, o.paid_amount AS paid, o.unpaid_amount AS unpaid,
                   COALESCE(t.line_count, 0) AS lineCount, COALESCE(t.line_total, 0) AS lineTotal,
                   l.order_line_id AS lineId, CASE WHEN p.product_id IS NOT NULL THEN p.product_category_id ELSE l.product_category_id END AS categoryId,
                   CASE WHEN p.product_id IS NOT NULL THEN p.product_category_code ELSE l.product_category_code END AS categoryCode,
                   CASE WHEN p.product_id IS NOT NULL THEN p.product_category_name ELSE l.product_category_name END AS categoryName,
                   l.product_id AS productId, l.product_code AS productCode, l.product_name AS productName,
                   l.product_variant_id AS skuId, l.sku_code AS skuCode, l.unit_code AS unitCode,
                   l.quantity AS quantity, l.line_amount AS salesAmount,
                   l.sales_net_amount AS salesNetAmount, l.refund_amount AS refundAmount,
                   o.synced_time AS orderSyncedAt, l.synced_time AS lineSyncedAt,
                   l.specification_snapshot AS specification, o.customer_name AS customerName,
                   o.owner_staff_name AS ownerStaffName,
                   CASE WHEN p.product_id IS NOT NULL THEN p.brand_id ELSE l.brand_id END AS brandId,
                   CASE WHEN p.product_id IS NOT NULL THEN p.brand_name ELSE l.brand_name END AS brandName
              FROM scoped_orders o
              LEFT JOIN line_totals t ON t.tenant_id = o.tenant_id AND t.order_id = o.order_id
              LEFT JOIN bi_sales_order_line_fact l
                ON l.tenant_id = o.tenant_id AND l.order_id = o.order_id AND l.deleted = 0
              LEFT JOIN bi_product_dim p ON p.tenant_id = l.tenant_id AND p.product_id = l.product_id
             ORDER BY o.order_date, o.order_id, l.order_line_id
             LIMIT #{lineLimit}
            </script>
            """)
    List<FactRow> load(
            @Param("tenantId") String tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode, @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode, @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode,
            @Param("brandId") Long brandId, @Param("productId") Long productId, @Param("skuId") Long skuId,
            @Param("orderLimit") int orderLimit, @Param("lineLimit") int lineLimit);
}
