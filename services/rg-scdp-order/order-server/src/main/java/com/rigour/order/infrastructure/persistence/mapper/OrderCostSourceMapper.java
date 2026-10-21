package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderCostSourceMapper extends BaseMapper<InternalSalesOrderEntity> {
    @Select(
            """
SELECT CAST(p.id AS CHAR) sourceId,o.order_no orderNo,DATE(CONVERT_TZ(p.payment_time,'+00:00','+08:00')) `date`,p.paid_amount amount,'PAYMENT' kind
FROM order_payment_record p JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
WHERE p.tenant_id=#{tenant} AND o.owner_employee_code=#{employee} AND p.deleted=0 AND o.deleted=0
  AND o.order_status_code<>'CANCELLED' AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time>=#{from} AND p.payment_time<#{until}
UNION ALL
SELECT CONCAT('REFUND:',r.id),o.order_no,DATE(CONVERT_TZ(r.refund_time,'+00:00','+08:00')),-r.refund_amount,'REFUND'
FROM order_refund_record r JOIN order_sales_order o ON o.tenant_id=r.tenant_id AND o.id=r.order_id
WHERE r.tenant_id=#{tenant} AND o.owner_employee_code=#{employee} AND r.deleted=0 AND o.deleted=0
  AND r.refund_status_code='CONFIRMED' AND r.refund_time>=#{from} AND r.refund_time<#{until}
ORDER BY `date`,sourceId
""")
    List<OrderCollectionSourceView> collections(
            @Param("tenant") String tenant,
            @Param("employee") String employee,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    @Select(
            """
SELECT o.id,o.order_no documentNo,o.region_code cityCode,o.selected_warehouse_id warehouseId,
o.customer_code_snapshot customerCode,o.customer_name_snapshot customerName,o.order_status_code statusCode,
COALESCE(NULLIF(o.source_system_code,''),'SYSTEM') sourceSystem,o.source_order_no originalNo,DATE(CONVERT_TZ(o.order_date,'+00:00','+08:00')) documentDate,
NULL destinationAddress
FROM order_sales_order o ${ew.customSqlSegment}
""")
    List<OrderDeliveryCostSourceView> deliveries(@Param(Constants.WRAPPER) Wrapper<?> query);

    @Select(
            """
 <script>
SELECT CAST(l.id AS CHAR) lineId,o.order_no orderNo,o.owner_employee_code employeeCode,o.region_code cityCode,l.product_id productId,l.product_variant_id productVariantId,
  l.product_name_snapshot productName,l.unit_code unitCode,DATE(CONVERT_TZ(o.order_date,'+00:00','+08:00')) orderDate,l.quantity,l.line_amount lineAmount,
  SUM(l.line_amount) OVER (PARTITION BY o.id) orderLineTotal,o.payable_amount orderAmount,
  (SELECT COALESCE(SUM(p.paid_amount),0) FROM order_payment_record p WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time&lt;cutoff.end_utc) paidAmount,
  (SELECT DATE(CONVERT_TZ(MAX(p.payment_time),'+00:00','+08:00')) FROM order_payment_record p WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time&lt;cutoff.end_utc) paymentDate,
  EXISTS(SELECT 1 FROM order_refund_record r WHERE r.tenant_id=o.tenant_id AND r.order_id=o.id
    AND r.deleted=0 AND r.refund_status_code='CONFIRMED') refundExists,
  CASE WHEN ABS(o.paid_amount-(SELECT COALESCE(SUM(p.paid_amount),0) FROM order_payment_record p WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED')))&gt;0.01 THEN 'PAYMENT_MISMATCH' ELSE o.data_quality_status_code END qualityStatus
FROM (SELECT #{until} AS end_utc) cutoff CROSS JOIN order_sales_order o JOIN order_sales_order_line l ON l.tenant_id=o.tenant_id AND l.order_id=o.id
WHERE o.tenant_id=#{tenant} AND <choose><when test="employee != null and employee != ''">o.owner_employee_code=#{employee}</when><otherwise>o.region_code=#{city}</otherwise></choose> AND o.deleted=0 AND l.deleted=0
  AND o.order_status_code NOT IN ('DRAFT','CANCELLED') AND o.order_date&lt;cutoff.end_utc ORDER BY o.id,l.id LIMIT 20001
 </script>
""")
    List<OrderCommissionSourceLineView> commissions(
            @Param("tenant") String tenant,
            @Param("employee") String employee,
            @Param("city") String city,
            @Param("until") LocalDateTime until);

    @Select(
            """
<script>
WITH scoped AS (
 SELECT o.* FROM order_sales_order o
 LEFT JOIN order_attribution_snapshot scope_attr ON scope_attr.tenant_id=o.tenant_id AND scope_attr.order_id=o.id AND scope_attr.state='FROZEN'
 WHERE o.tenant_id=#{tenant} AND o.deleted=0 AND o.order_status_code NOT IN ('DRAFT','CANCELLED')
 AND o.order_date&lt;#{until}
 AND <choose><when test="employee != null and employee != ''">COALESCE(scope_attr.employee_code,o.owner_employee_code)=#{employee}</when><otherwise>COALESCE(scope_attr.region_code,o.region_code)=#{city}</otherwise></choose>
), running AS (
 SELECT p.order_id,p.payment_time,p.id,
 SUM(p.paid_amount) OVER(PARTITION BY p.tenant_id,p.order_id ORDER BY p.payment_time,p.id ROWS UNBOUNDED PRECEDING) cumulative
 FROM order_payment_record p JOIN scoped o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
 WHERE p.tenant_id=#{tenant} AND p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time&lt;#{until}
), paid AS (
 SELECT r.order_id,MIN(r.payment_time) fully_paid_time FROM running r JOIN scoped o ON o.id=r.order_id
 WHERE o.payable_amount&gt;0 AND r.cumulative-COALESCE((SELECT SUM(f.refund_amount) FROM order_refund_record f
 WHERE f.tenant_id=o.tenant_id AND f.order_id=o.id AND f.deleted=0 AND f.refund_status_code='CONFIRMED'
 AND f.refund_time&lt;=r.payment_time),0)&gt;=o.payable_amount GROUP BY r.order_id
), refunds AS (
 SELECT f.order_id,SUM(f.refund_amount) amount,MAX(f.refund_time) last_time FROM order_refund_record f
 JOIN scoped o ON o.tenant_id=f.tenant_id AND o.id=f.order_id
 WHERE f.tenant_id=#{tenant} AND f.deleted=0 AND f.refund_status_code='CONFIRMED' AND f.refund_time&lt;#{until} GROUP BY f.order_id
)
SELECT CAST(l.id AS CHAR) lineId,CAST(o.id AS CHAR) orderId,o.order_no orderNo,
 COALESCE(a.employee_code,o.owner_employee_code) employeeCode,COALESCE(a.region_code,o.region_code) cityCode,a.department_id departmentId,
 l.product_id productId,l.product_variant_id productVariantId,l.product_name_snapshot productName,l.unit_code unitCode,
 DATE(CONVERT_TZ(o.order_date,'+00:00','+08:00')) orderDate,l.quantity,l.line_amount lineAmount,
 SUM(l.line_amount) OVER(PARTITION BY o.id) orderLineTotal,o.payable_amount orderAmount,
 COALESCE((SELECT SUM(p.paid_amount) FROM order_payment_record p WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time&lt;#{until}),0) paidAmount,
 DATE(CONVERT_TZ(paid.fully_paid_time,'+00:00','+08:00')) fullyPaidDate,COALESCE(refunds.amount,0) refundedAmount,
 DATE(CONVERT_TZ(refunds.last_time,'+00:00','+08:00')) lastRefundDate,
 CASE WHEN a.order_id IS NOT NULL THEN o.data_quality_status_code
      WHEN NULLIF(o.source_system_code,'') IS NOT NULL
       AND NULLIF(o.owner_employee_code,'') IS NOT NULL AND NULLIF(o.region_code,'') IS NOT NULL
       AND NOT EXISTS(SELECT 1 FROM order_attribution_snapshot pending
           WHERE pending.tenant_id=o.tenant_id AND pending.order_id=o.id)
      THEN o.data_quality_status_code ELSE 'ATTRIBUTION_PENDING' END qualityStatus
FROM scoped o JOIN order_sales_order_line l ON l.tenant_id=o.tenant_id AND l.order_id=o.id AND l.deleted=0
LEFT JOIN order_attribution_snapshot a ON a.tenant_id=o.tenant_id AND a.order_id=o.id AND a.state='FROZEN'
LEFT JOIN paid ON paid.order_id=o.id LEFT JOIN refunds ON refunds.order_id=o.id
ORDER BY o.id,l.id LIMIT 20001
</script>
""")
    List<com.rigour.order.api.v1.model.OrderPayrollLineView> payrollLines(
            @Param("tenant") String tenant,
            @Param("employee") String employee,
            @Param("city") String city,
            @Param("until") LocalDateTime until);
}
