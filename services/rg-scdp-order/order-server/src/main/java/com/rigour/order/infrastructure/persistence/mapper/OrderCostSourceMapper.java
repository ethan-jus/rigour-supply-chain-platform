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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderCostSourceMapper extends BaseMapper<InternalSalesOrderEntity> {
    @Select(
"""
SELECT CONCAT('PAYMENT:',p.id) sourceId,o.order_no orderNo,COALESCE(a.region_code,o.region_code) cityCode,
 DATE(CONVERT_TZ(p.actual_cash_at,'+00:00','+08:00')) `date`,p.paid_amount amount,
 CASE WHEN EXISTS (SELECT 1 FROM order_attribution_adjustment review WHERE review.tenant_id=o.tenant_id AND review.order_id=o.id AND review.status='PENDING') THEN 'ATTRIBUTION_PENDING'
      WHEN a.order_id IS NOT NULL THEN o.data_quality_status_code
      WHEN NULLIF(o.source_system_code,'') IS NOT NULL
       AND NULLIF(o.owner_employee_code,'') IS NOT NULL AND NULLIF(o.region_code,'') IS NOT NULL
       AND NOT EXISTS(SELECT 1 FROM order_attribution_snapshot pending
           WHERE pending.tenant_id=o.tenant_id AND pending.order_id=o.id)
      THEN o.data_quality_status_code ELSE 'ATTRIBUTION_PENDING' END qualityStatus
FROM order_verified_payment_v p JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
LEFT JOIN order_attribution_snapshot a ON a.tenant_id=o.tenant_id AND a.order_id=o.id AND a.state='FROZEN'
WHERE p.tenant_id=#{tenant} AND COALESCE(a.region_code,o.region_code)=#{city} AND p.deleted=0 AND o.deleted=0 AND o.order_status_code NOT IN ('CANCELLED','DELETED')
 AND p.payment_status_code IN ('RECEIVED','CHECKED')
 AND p.actual_cash_at>=#{from} AND p.actual_cash_at<#{until}
ORDER BY `date`,sourceId LIMIT 20001
""")
    List<com.rigour.order.api.v1.model.OrderCityCollectionSourceView> cityCollections(
            @Param("tenant") String tenant,
            @Param("city") String city,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    @Select(
"""
SELECT CAST(p.id AS CHAR) sourceId,o.order_no orderNo,DATE(CONVERT_TZ(p.payment_time,'+00:00','+08:00')) `date`,p.paid_amount amount,'PAYMENT' kind
FROM order_payment_record p JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
WHERE p.tenant_id=#{tenant} AND o.owner_employee_code=#{employee} AND p.deleted=0 AND o.deleted=0
  AND o.order_status_code NOT IN ('CANCELLED','DELETED') AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time>=#{from} AND p.payment_time<#{until}
UNION ALL
SELECT CONCAT('REFUND:',r.id),o.order_no,DATE(CONVERT_TZ(r.refund_time,'+00:00','+08:00')),-r.refund_amount,'REFUND'
FROM order_refund_record r JOIN order_sales_order o ON o.tenant_id=r.tenant_id AND o.id=r.order_id
WHERE r.tenant_id=#{tenant} AND o.owner_employee_code=#{employee} AND r.deleted=0 AND o.deleted=0 AND o.order_status_code NOT IN ('CANCELLED','DELETED')
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
NULL destinationAddress,COALESCE(snap.employee_code,o.owner_employee_code) employeeCode,
COALESCE(snap.employee_name,o.owner_employee_name_snapshot) employeeName,snap.department_id departmentId,snap.department_name departmentName,
o.contact_name_snapshot contactName,o.contact_phone_snapshot contactPhone,o.payable_amount documentAmount,
CASE WHEN o.selected_warehouse_id IS NULL THEN COALESCE(
  (SELECT CASE WHEN COUNT(DISTINCT NULLIF(TRIM(i.source_warehouse_name),'')) > 1 THEN '多个发货仓'
               ELSE MIN(NULLIF(TRIM(i.source_warehouse_name),'')) END
   FROM order_sales_shipment i
   WHERE i.tenant_id=o.tenant_id AND i.sales_order_id=o.id AND i.deleted=0 AND i.ship_time IS NOT NULL
     AND i.shipment_status_code NOT IN ('CANCELLED','VOID')),
  (SELECT CASE WHEN COUNT(DISTINCT NULLIF(TRIM(i.source_warehouse_name),'')) > 1 THEN '多个发货仓'
               ELSE MIN(NULLIF(TRIM(i.source_warehouse_name),'')) END
   FROM order_sales_shipment i
   WHERE o.source_system_code='DINGHUOBAO' AND i.source_system_code='DINGHUOBAO'
     AND i.tenant_id=o.tenant_id AND i.sales_order_no_snapshot=o.source_order_no AND i.sales_order_id IS NULL
     AND i.deleted=0 AND i.ship_time IS NOT NULL AND i.shipment_status_code NOT IN ('CANCELLED','VOID')
     AND NOT EXISTS (SELECT 1 FROM order_sales_order duplicate_order
                     WHERE duplicate_order.tenant_id=o.tenant_id AND duplicate_order.id<>o.id
                       AND duplicate_order.source_system_code=o.source_system_code
                       AND duplicate_order.source_order_no=o.source_order_no AND duplicate_order.deleted=0))
) END shipmentWarehouseName
FROM order_sales_order o
LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN'
${ew.customSqlSegment}
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
  AND o.order_status_code NOT IN ('DRAFT','CANCELLED','DELETED') AND o.order_date&lt;cutoff.end_utc ORDER BY o.id,l.id LIMIT 20001
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
 WHERE o.tenant_id=#{tenant} AND o.deleted=0 AND o.order_status_code NOT IN ('DRAFT','CANCELLED','DELETED')
 AND o.order_date&lt;#{until}
 AND <choose><when test="employee != null and employee != ''">COALESCE(scope_attr.employee_code,o.owner_employee_code)=#{employee}</when><otherwise>COALESCE(scope_attr.region_code,o.region_code)=#{city}</otherwise></choose>
), running AS (
 SELECT p.order_id,p.allocation_effective_at payment_time,p.id,
 SUM(p.paid_amount) OVER(PARTITION BY p.tenant_id,p.order_id ORDER BY p.allocation_effective_at,p.id ROWS UNBOUNDED PRECEDING) cumulative
 FROM order_verified_payment_v p JOIN scoped o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
 WHERE p.tenant_id=#{tenant} AND p.allocation_effective_at&lt;#{until} AND p.actual_cash_at&lt;#{until}
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
 COALESCE((SELECT MAX(r.cumulative) FROM running r WHERE r.order_id=o.id),0) paidAmount,
 DATE(CONVERT_TZ(paid.fully_paid_time,'+00:00','+08:00')) fullyPaidDate,COALESCE(refunds.amount,0) refundedAmount,
 DATE(CONVERT_TZ(refunds.last_time,'+00:00','+08:00')) lastRefundDate,
 CASE WHEN EXISTS (SELECT 1 FROM order_attribution_adjustment review WHERE review.tenant_id=o.tenant_id AND review.order_id=o.id AND review.status='PENDING') THEN 'ATTRIBUTION_PENDING'
      WHEN EXISTS (SELECT 1 FROM order_financial_event e WHERE e.tenant_id=o.tenant_id AND e.order_id=o.id AND e.deleted=0 AND e.event_type IN ('CREDIT_NOTE','WRITE_OFF') AND e.effective_at&lt;#{until}) THEN 'NON_CASH_SETTLEMENT'
      WHEN a.order_id IS NOT NULL THEN o.data_quality_status_code
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

    // 候选订单必须保留其他回款人的核销记录，才能找到首次达到整单应收金额的那笔。
    @Select(
"""
WITH scoped AS (
    SELECT o.*,COALESCE(a.employee_code,o.owner_employee_code) AS original_employee_code
    FROM order_sales_order o
    LEFT JOIN order_attribution_snapshot a ON a.tenant_id=o.tenant_id AND a.order_id=o.id AND a.state='FROZEN'
    WHERE o.tenant_id=#{tenant} AND o.deleted=0 AND o.order_date<#{until}
      AND o.order_status_code NOT IN ('DRAFT','CANCELLED','DELETED') AND o.payable_amount>0
      AND (COALESCE(a.employee_code,o.owner_employee_code)=#{employee} OR EXISTS (
          SELECT 1 FROM order_verified_payment_v p
          WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.payment_owner_code=#{employee}
            AND p.allocation_effective_at<#{until} AND p.actual_cash_at<#{until}
      ))
), payments AS (
    SELECT p.tenant_id,p.order_id,p.id,p.payment_no,p.paid_amount,p.actual_cash_at,
           NULLIF(TRIM(p.payment_owner_code),'') AS owner_code,
           NULLIF(TRIM(p.payment_owner_name),'') AS owner_name,
           NULLIF(TRIM(p.payment_owner_evidence),'') AS owner_evidence,
           GREATEST(p.allocation_effective_at,p.actual_cash_at) AS payment_time
    FROM order_verified_payment_v p
    JOIN scoped o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
    WHERE p.tenant_id=#{tenant} AND p.allocation_effective_at<#{until} AND p.actual_cash_at<#{until}
), running AS (
    SELECT p.*,SUM(p.paid_amount) OVER (
        PARTITION BY p.tenant_id,p.order_id ORDER BY p.payment_time,p.id ROWS UNBOUNDED PRECEDING
    ) AS cumulative
    FROM payments p
), crossings AS (
    SELECT r.*,ROW_NUMBER() OVER (PARTITION BY r.tenant_id,r.order_id ORDER BY r.payment_time,r.id) AS settlement_rank
    FROM running r
    JOIN scoped o ON o.tenant_id=r.tenant_id AND o.id=r.order_id
    WHERE r.cumulative-COALESCE((
        SELECT SUM(f.refund_amount)
        FROM order_refund_record f
        WHERE f.tenant_id=o.tenant_id AND f.order_id=o.id AND f.deleted=0
          AND f.refund_status_code='CONFIRMED' AND f.refund_time<=r.payment_time
    ),0)>=o.payable_amount
)
SELECT o.id AS orderId,o.order_no AS orderNo,o.original_employee_code AS originalEmployeeCode,
       CAST(p.id AS CHAR) AS paymentId,p.payment_no AS paymentNo,
       CASE WHEN p.owner_evidence IS NOT NULL THEN p.owner_code END AS paymentOwnerCode,
       CASE WHEN p.owner_evidence IS NOT NULL AND p.owner_code IS NOT NULL THEN p.owner_name END AS paymentOwnerName,
       p.owner_evidence AS paymentOwnerEvidence,
       DATE(CONVERT_TZ(p.payment_time,'+00:00','+08:00')) AS fullyPaidDate,
       DATE(CONVERT_TZ(p.actual_cash_at,'+00:00','+08:00')) AS actualCashDate,
       o.payable_amount AS orderAmount,
       COALESCE((
           SELECT SUM(f.refund_amount)
           FROM order_refund_record f
           WHERE f.tenant_id=o.tenant_id AND f.order_id=o.id AND f.deleted=0
             AND f.refund_status_code='CONFIRMED' AND f.refund_time<#{until}
       ),0) AS refundedAmount,
       CASE
           WHEN p.owner_code IS NULL OR p.owner_evidence IS NULL THEN 'PAYMENT_OWNER_PENDING'
           WHEN EXISTS (
               SELECT 1 FROM order_attribution_adjustment a
               WHERE a.tenant_id=o.tenant_id AND a.order_id=o.id AND a.status='PENDING'
           ) THEN 'ATTRIBUTION_PENDING'
           WHEN EXISTS (
               SELECT 1 FROM order_financial_event e
               WHERE e.tenant_id=o.tenant_id AND e.order_id=o.id AND e.deleted=0
                 AND e.event_type IN ('CREDIT_NOTE','WRITE_OFF') AND e.effective_at<#{until}
           ) THEN 'NON_CASH_SETTLEMENT'
           WHEN EXISTS (
                 SELECT 1 FROM order_attribution_snapshot a
                 WHERE a.tenant_id=o.tenant_id AND a.order_id=o.id AND a.state='REVIEW'
           ) THEN 'ATTRIBUTION_PENDING'
           ELSE o.data_quality_status_code
       END AS qualityStatus
FROM scoped o
JOIN crossings p ON p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.settlement_rank=1
WHERE p.payment_time>=#{from}
  AND (
      p.owner_code=#{employee}
      OR (
          (p.owner_code IS NULL OR p.owner_evidence IS NULL)
          AND (o.original_employee_code=#{employee} OR EXISTS (
              SELECT 1 FROM payments own
              WHERE own.tenant_id=p.tenant_id AND own.order_id=p.order_id AND own.owner_code=#{employee}
                AND (own.payment_time<p.payment_time OR (own.payment_time=p.payment_time AND own.id<p.id))
          ))
      )
  )
ORDER BY p.payment_time,p.id,o.id LIMIT 20001
""")
    List<PayrollSettlement> payrollSettlements(
            @Param("tenant") String tenant,
            @Param("employee") String employee,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    @Select(
"""
<script>
SELECT l.order_id AS orderId,CAST(l.id AS CHAR) AS lineId,l.product_id AS productId,
       l.product_variant_id AS productVariantId,l.product_name_snapshot AS productName,
       l.unit_code AS unit,l.quantity,l.line_amount AS lineAmount
FROM order_sales_order_line l
WHERE l.tenant_id=#{tenant} AND l.deleted=0
  AND l.order_id IN <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
ORDER BY l.order_id,l.id LIMIT 20001
</script>
""")
    List<PayrollProductLine> payrollProductLines(
            @Param("tenant") String tenant, @Param("orderIds") List<Long> orderIds);

    record PayrollSettlement(
            long orderId,
            String orderNo,
            String originalEmployeeCode,
            String paymentId,
            String paymentNo,
            String paymentOwnerCode,
            String paymentOwnerName,
            String paymentOwnerEvidence,
            LocalDate fullyPaidDate,
            LocalDate actualCashDate,
            BigDecimal orderAmount,
            BigDecimal refundedAmount,
            String qualityStatus) {}

    record PayrollProductLine(
            long orderId,
            String lineId,
            Long productId,
            Long productVariantId,
            String productName,
            String unit,
            BigDecimal quantity,
            BigDecimal lineAmount) {}
}
