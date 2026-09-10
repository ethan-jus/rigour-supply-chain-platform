package com.rigour.analytics.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.analytics.infrastructure.persistence.entity.SupplyDashboardSourceMarkerEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 供应链 BI 聚合和刷新 Mapper。 */
public interface SupplyDashboardQueryMapper extends BaseMapper<SupplyDashboardSourceMarkerEntity> {
    @Select("""
            SELECT MAX(order_date)
              FROM bi_sales_order_fact
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
               AND order_status_code <> 'CANCELLED'
            """)
    LocalDateTime latestSalesOrderDate(@Param("tenantId") String tenantId);

    @Select("""
            <script>
            SELECT COUNT(*) AS orderCount,
                   COUNT(DISTINCT o.customer_id) AS orderingCustomerCount,
                   COUNT(DISTINCT CASE WHEN repeat_orders.customerOrderCount &gt;= 2 THEN o.customer_id END)
                        AS repeatCustomerCount,
                   COALESCE(SUM(o.total_quantity), 0) AS totalQuantity,
                   COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                   COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                   COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                   COALESCE(SUM(CASE WHEN o.unpaid_amount &gt; 0 THEN 1 ELSE 0 END), 0) AS unpaidOrderCount,
                   MAX(o.source_updated_time) AS latestUpdatedTime
              FROM bi_sales_order_fact o
              LEFT JOIN (
                    SELECT customer_id, COUNT(*) AS customerOrderCount
                      FROM bi_sales_order_fact
                     WHERE tenant_id = #{tenantId}
                       AND deleted = 0
                       AND order_status_code &lt;&gt; 'CANCELLED'
                       AND order_date &gt;= #{from}
                       AND order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND source_system_code = #{sourceSystemCode}
            </if>
                     GROUP BY customer_id
              ) repeat_orders ON repeat_orders.customer_id = o.customer_id
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND o.source_system_code = #{sourceSystemCode}
            </if>
            </script>
            """)
    Map<String, Object> salesSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT COUNT(*) AS activeCustomerCount,
                   COUNT(CASE WHEN c.has_contact = 1 THEN 1 END) AS contactedCustomerCount,
                   MAX(c.source_updated_time) AS latestUpdatedTime
              FROM bi_customer_dim c
             WHERE c.tenant_id = #{tenantId}
               AND c.deleted = 0
               AND c.status_code = 'ACTIVE'
            <if test="regionCode != null">
               AND c.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND c.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND c.customer_type_code = #{customerTypeCode}
            </if>
            </script>
            """)
    Map<String, Object> customerSummary(
            @Param("tenantId") String tenantId,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode);

    @Select("""
            <script>
            WITH base_customers AS (
                SELECT c.customer_id AS customerId,
                       COALESCE(c.customer_code, CAST(c.customer_id AS CHAR), 'UNKNOWN') AS customerCode,
                       COALESCE(c.customer_name, c.customer_code, CAST(c.customer_id AS CHAR), '未知客户') AS customerName,
                       c.region_code AS regionCode,
                       c.region_name AS regionName,
                       c.owner_staff_code AS ownerStaffCode,
                       c.owner_staff_name AS ownerStaffName,
                       c.customer_type_code AS customerTypeCode,
                       c.customer_type_name AS customerTypeName
                  FROM bi_customer_dim c
                 WHERE c.tenant_id = #{tenantId}
                   AND c.deleted = 0
                   AND c.status_code = 'ACTIVE'
            <if test="regionCode != null">
                   AND c.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                   AND c.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                   AND c.customer_type_code = #{customerTypeCode}
            </if>
            ),
            period_orders AS (
                SELECT o.customer_id AS customerId,
                       COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                       COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                       COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                       COUNT(*) AS orderCount
                  FROM bi_sales_order_fact o
                  JOIN base_customers c ON c.customerId = o.customer_id
                 WHERE o.tenant_id = #{tenantId}
                   AND o.deleted = 0
                   AND o.order_status_code &lt;&gt; 'CANCELLED'
                   AND o.order_date &gt;= #{from}
                   AND o.order_date &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND o.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY o.customer_id
            ),
            history_orders AS (
                SELECT o.customer_id AS customerId,
                       MAX(o.order_date) AS lastOrderTime
                  FROM bi_sales_order_fact o
                  JOIN base_customers c ON c.customerId = o.customer_id
                 WHERE o.tenant_id = #{tenantId}
                   AND o.deleted = 0
                   AND o.order_status_code &lt;&gt; 'CANCELLED'
                   AND o.order_date &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND o.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY o.customer_id
            ),
            period_payments AS (
                SELECT p.customer_id AS customerId,
                       COUNT(*) AS paymentCount,
                       MAX(p.payment_time) AS lastPaymentTime
                  FROM bi_sales_payment_fact p
                  JOIN base_customers c ON c.customerId = p.customer_id
                 WHERE p.tenant_id = #{tenantId}
                   AND p.deleted = 0
                   AND p.payment_time &gt;= #{from}
                   AND p.payment_time &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND p.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY p.customer_id
            ),
            customer_metrics AS (
                SELECT c.customerId,
                       c.customerCode,
                       c.customerName,
                       c.regionCode,
                       c.regionName,
                       c.ownerStaffCode,
                       c.ownerStaffName,
                       c.customerTypeCode,
                       c.customerTypeName,
                       COALESCE(po.salesAmount, 0) AS salesAmount,
                       COALESCE(po.paidAmount, 0) AS paidAmount,
                       COALESCE(po.unpaidAmount, 0) AS unpaidAmount,
                       COALESCE(po.orderCount, 0) AS orderCount,
                       COALESCE(pp.paymentCount, 0) AS paymentCount,
                       ho.lastOrderTime,
                       pp.lastPaymentTime,
                       CASE WHEN ho.lastOrderTime IS NULL THEN 9999
                            ELSE GREATEST(DATEDIFF(#{to}, ho.lastOrderTime), 0) END AS inactiveDays
                  FROM base_customers c
                  LEFT JOIN period_orders po ON po.customerId = c.customerId
                  LEFT JOIN history_orders ho ON ho.customerId = c.customerId
                  LEFT JOIN period_payments pp ON pp.customerId = c.customerId
            ),
            ranked_customers AS (
                SELECT customer_metrics.*,
                       COALESCE(SUM(salesAmount) OVER (), 0) AS totalSalesAmount,
                       COALESCE(SUM(salesAmount) OVER (
                           ORDER BY salesAmount DESC, customerId
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                       ), 0) AS cumulativeSalesAmount
                  FROM customer_metrics
            ),
            segmented_customers AS (
                SELECT ranked_customers.*,
                       CASE
                           WHEN totalSalesAmount &lt;= 0 OR salesAmount &lt;= 0 THEN 'C'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.80 THEN 'A'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.95 THEN 'B'
                           ELSE 'C'
                       END AS segmentCode,
                       CASE
                           WHEN totalSalesAmount &lt;= 0 OR salesAmount &lt;= 0 THEN 'C级客户'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.80 THEN 'A级客户'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.95 THEN 'B级客户'
                           ELSE 'C级客户'
                       END AS segmentName,
                       LEAST(100, GREATEST(0,
                           orderCount * 12
                           + paymentCount * 8
                           + CASE WHEN inactiveDays &gt;= 60 THEN 0
                                  WHEN inactiveDays &gt;= 30 THEN 15
                                  ELSE 30 END
                           + LEAST(salesAmount / 1000, 30)
                       )) AS activityScore,
                       CASE WHEN lastOrderTime IS NULL OR inactiveDays &gt;= 60 THEN 'HIGH'
                            WHEN inactiveDays &gt;= 30 THEN 'MEDIUM'
                            ELSE 'LOW' END AS churnRiskLevel
                  FROM ranked_customers
            )
            SELECT segmentCode,
                   segmentName,
                   COUNT(*) AS customerCount,
                   COALESCE(SUM(salesAmount), 0) AS salesAmount,
                   COALESCE(SUM(paidAmount), 0) AS paidAmount,
                   COALESCE(SUM(unpaidAmount), 0) AS unpaidAmount,
                   COALESCE(AVG(activityScore), 0) AS averageActivityScore,
                   COALESCE(SUM(CASE WHEN churnRiskLevel &lt;&gt; 'LOW' THEN 1 ELSE 0 END), 0)
                       AS churnRiskCustomerCount
              FROM segmented_customers
             GROUP BY segmentCode, segmentName
             ORDER BY CASE segmentCode WHEN 'A' THEN 1 WHEN 'B' THEN 2 ELSE 3 END
            </script>
            """)
    List<Map<String, Object>> customerSegmentSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            WITH base_customers AS (
                SELECT c.customer_id AS customerId,
                       COALESCE(c.customer_code, CAST(c.customer_id AS CHAR), 'UNKNOWN') AS customerCode,
                       COALESCE(c.customer_name, c.customer_code, CAST(c.customer_id AS CHAR), '未知客户') AS customerName,
                       c.region_code AS regionCode,
                       c.region_name AS regionName,
                       c.owner_staff_code AS ownerStaffCode,
                       c.owner_staff_name AS ownerStaffName,
                       c.customer_type_code AS customerTypeCode,
                       c.customer_type_name AS customerTypeName
                  FROM bi_customer_dim c
                 WHERE c.tenant_id = #{tenantId}
                   AND c.deleted = 0
                   AND c.status_code = 'ACTIVE'
            <if test="regionCode != null">
                   AND c.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                   AND c.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                   AND c.customer_type_code = #{customerTypeCode}
            </if>
            ),
            period_orders AS (
                SELECT o.customer_id AS customerId,
                       COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                       COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                       COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                       COUNT(*) AS orderCount
                  FROM bi_sales_order_fact o
                  JOIN base_customers c ON c.customerId = o.customer_id
                 WHERE o.tenant_id = #{tenantId}
                   AND o.deleted = 0
                   AND o.order_status_code &lt;&gt; 'CANCELLED'
                   AND o.order_date &gt;= #{from}
                   AND o.order_date &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND o.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY o.customer_id
            ),
            history_orders AS (
                SELECT o.customer_id AS customerId,
                       MAX(o.order_date) AS lastOrderTime
                  FROM bi_sales_order_fact o
                  JOIN base_customers c ON c.customerId = o.customer_id
                 WHERE o.tenant_id = #{tenantId}
                   AND o.deleted = 0
                   AND o.order_status_code &lt;&gt; 'CANCELLED'
                   AND o.order_date &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND o.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY o.customer_id
            ),
            period_payments AS (
                SELECT p.customer_id AS customerId,
                       COUNT(*) AS paymentCount,
                       MAX(p.payment_time) AS lastPaymentTime
                  FROM bi_sales_payment_fact p
                  JOIN base_customers c ON c.customerId = p.customer_id
                 WHERE p.tenant_id = #{tenantId}
                   AND p.deleted = 0
                   AND p.payment_time &gt;= #{from}
                   AND p.payment_time &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND p.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY p.customer_id
            ),
            customer_metrics AS (
                SELECT c.customerId,
                       c.customerCode,
                       c.customerName,
                       c.regionCode,
                       c.regionName,
                       c.ownerStaffCode,
                       c.ownerStaffName,
                       c.customerTypeCode,
                       c.customerTypeName,
                       COALESCE(po.salesAmount, 0) AS salesAmount,
                       COALESCE(po.paidAmount, 0) AS paidAmount,
                       COALESCE(po.unpaidAmount, 0) AS unpaidAmount,
                       COALESCE(po.orderCount, 0) AS orderCount,
                       COALESCE(pp.paymentCount, 0) AS paymentCount,
                       ho.lastOrderTime,
                       pp.lastPaymentTime,
                       CASE WHEN ho.lastOrderTime IS NULL THEN 9999
                            ELSE GREATEST(DATEDIFF(#{to}, ho.lastOrderTime), 0) END AS inactiveDays
                  FROM base_customers c
                  LEFT JOIN period_orders po ON po.customerId = c.customerId
                  LEFT JOIN history_orders ho ON ho.customerId = c.customerId
                  LEFT JOIN period_payments pp ON pp.customerId = c.customerId
            ),
            ranked_customers AS (
                SELECT customer_metrics.*,
                       COALESCE(SUM(salesAmount) OVER (), 0) AS totalSalesAmount,
                       COALESCE(SUM(salesAmount) OVER (
                           ORDER BY salesAmount DESC, customerId
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                       ), 0) AS cumulativeSalesAmount
                  FROM customer_metrics
            ),
            segmented_customers AS (
                SELECT ranked_customers.*,
                       CASE
                           WHEN totalSalesAmount &lt;= 0 OR salesAmount &lt;= 0 THEN 'C'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.80 THEN 'A'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.95 THEN 'B'
                           ELSE 'C'
                       END AS segmentCode,
                       CASE
                           WHEN totalSalesAmount &lt;= 0 OR salesAmount &lt;= 0 THEN 'C级客户'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.80 THEN 'A级客户'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.95 THEN 'B级客户'
                           ELSE 'C级客户'
                       END AS segmentName,
                       LEAST(100, GREATEST(0,
                           orderCount * 12
                           + paymentCount * 8
                           + CASE WHEN inactiveDays &gt;= 60 THEN 0
                                  WHEN inactiveDays &gt;= 30 THEN 15
                                  ELSE 30 END
                           + LEAST(salesAmount / 1000, 30)
                       )) AS activityScore,
                       CASE WHEN lastOrderTime IS NULL OR inactiveDays &gt;= 60 THEN 'HIGH'
                            WHEN inactiveDays &gt;= 30 THEN 'MEDIUM'
                            ELSE 'LOW' END AS churnRiskLevel
                  FROM ranked_customers
            )
            SELECT customerCode,
                   customerName,
                   regionCode,
                   regionName,
                   ownerStaffCode,
                   ownerStaffName,
                   customerTypeCode,
                   customerTypeName,
                   segmentCode,
                   segmentName,
                   salesAmount,
                   paidAmount,
                   unpaidAmount,
                   orderCount,
                   paymentCount,
                   lastOrderTime,
                   lastPaymentTime,
                   inactiveDays,
                   activityScore,
                   churnRiskLevel
              FROM segmented_customers
             ORDER BY activityScore DESC, salesAmount DESC, customerCode
             LIMIT 80
            </script>
            """)
    List<Map<String, Object>> customerActivityRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            WITH base_customers AS (
                SELECT c.customer_id AS customerId,
                       COALESCE(c.customer_code, CAST(c.customer_id AS CHAR), 'UNKNOWN') AS customerCode,
                       COALESCE(c.customer_name, c.customer_code, CAST(c.customer_id AS CHAR), '未知客户') AS customerName,
                       c.region_code AS regionCode,
                       c.region_name AS regionName,
                       c.owner_staff_code AS ownerStaffCode,
                       c.owner_staff_name AS ownerStaffName,
                       c.customer_type_code AS customerTypeCode,
                       c.customer_type_name AS customerTypeName
                  FROM bi_customer_dim c
                 WHERE c.tenant_id = #{tenantId}
                   AND c.deleted = 0
                   AND c.status_code = 'ACTIVE'
            <if test="regionCode != null">
                   AND c.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                   AND c.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                   AND c.customer_type_code = #{customerTypeCode}
            </if>
            ),
            period_orders AS (
                SELECT o.customer_id AS customerId,
                       COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                       COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                       COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                       COUNT(*) AS orderCount
                  FROM bi_sales_order_fact o
                  JOIN base_customers c ON c.customerId = o.customer_id
                 WHERE o.tenant_id = #{tenantId}
                   AND o.deleted = 0
                   AND o.order_status_code &lt;&gt; 'CANCELLED'
                   AND o.order_date &gt;= #{from}
                   AND o.order_date &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND o.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY o.customer_id
            ),
            history_orders AS (
                SELECT o.customer_id AS customerId,
                       MAX(o.order_date) AS lastOrderTime
                  FROM bi_sales_order_fact o
                  JOIN base_customers c ON c.customerId = o.customer_id
                 WHERE o.tenant_id = #{tenantId}
                   AND o.deleted = 0
                   AND o.order_status_code &lt;&gt; 'CANCELLED'
                   AND o.order_date &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND o.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY o.customer_id
            ),
            period_payments AS (
                SELECT p.customer_id AS customerId,
                       COUNT(*) AS paymentCount,
                       MAX(p.payment_time) AS lastPaymentTime
                  FROM bi_sales_payment_fact p
                  JOIN base_customers c ON c.customerId = p.customer_id
                 WHERE p.tenant_id = #{tenantId}
                   AND p.deleted = 0
                   AND p.payment_time &gt;= #{from}
                   AND p.payment_time &lt;= #{to}
            <if test="sourceSystemCode != null">
                   AND p.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY p.customer_id
            ),
            customer_metrics AS (
                SELECT c.customerId,
                       c.customerCode,
                       c.customerName,
                       c.regionCode,
                       c.regionName,
                       c.ownerStaffCode,
                       c.ownerStaffName,
                       c.customerTypeCode,
                       c.customerTypeName,
                       COALESCE(po.salesAmount, 0) AS salesAmount,
                       COALESCE(po.paidAmount, 0) AS paidAmount,
                       COALESCE(po.unpaidAmount, 0) AS unpaidAmount,
                       COALESCE(po.orderCount, 0) AS orderCount,
                       COALESCE(pp.paymentCount, 0) AS paymentCount,
                       ho.lastOrderTime,
                       pp.lastPaymentTime,
                       CASE WHEN ho.lastOrderTime IS NULL THEN 9999
                            ELSE GREATEST(DATEDIFF(#{to}, ho.lastOrderTime), 0) END AS inactiveDays
                  FROM base_customers c
                  LEFT JOIN period_orders po ON po.customerId = c.customerId
                  LEFT JOIN history_orders ho ON ho.customerId = c.customerId
                  LEFT JOIN period_payments pp ON pp.customerId = c.customerId
            ),
            ranked_customers AS (
                SELECT customer_metrics.*,
                       COALESCE(SUM(salesAmount) OVER (), 0) AS totalSalesAmount,
                       COALESCE(SUM(salesAmount) OVER (
                           ORDER BY salesAmount DESC, customerId
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                       ), 0) AS cumulativeSalesAmount
                  FROM customer_metrics
            ),
            segmented_customers AS (
                SELECT ranked_customers.*,
                       CASE
                           WHEN totalSalesAmount &lt;= 0 OR salesAmount &lt;= 0 THEN 'C'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.80 THEN 'A'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.95 THEN 'B'
                           ELSE 'C'
                       END AS segmentCode,
                       CASE
                           WHEN totalSalesAmount &lt;= 0 OR salesAmount &lt;= 0 THEN 'C级客户'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.80 THEN 'A级客户'
                           WHEN (cumulativeSalesAmount - salesAmount) / totalSalesAmount &lt; 0.95 THEN 'B级客户'
                           ELSE 'C级客户'
                       END AS segmentName,
                       LEAST(100, GREATEST(0,
                           orderCount * 12
                           + paymentCount * 8
                           + CASE WHEN inactiveDays &gt;= 60 THEN 0
                                  WHEN inactiveDays &gt;= 30 THEN 15
                                  ELSE 30 END
                           + LEAST(salesAmount / 1000, 30)
                       )) AS activityScore,
                       CASE WHEN lastOrderTime IS NULL OR inactiveDays &gt;= 60 THEN 'HIGH'
                            WHEN inactiveDays &gt;= 30 THEN 'MEDIUM'
                            ELSE 'LOW' END AS churnRiskLevel
                  FROM ranked_customers
            )
            SELECT customerCode,
                   customerName,
                   regionCode,
                   regionName,
                   ownerStaffCode,
                   ownerStaffName,
                   customerTypeCode,
                   customerTypeName,
                   segmentCode,
                   segmentName,
                   salesAmount,
                   paidAmount,
                   unpaidAmount,
                   orderCount,
                   paymentCount,
                   lastOrderTime,
                   lastPaymentTime,
                   inactiveDays,
                   activityScore,
                   churnRiskLevel
              FROM segmented_customers
             WHERE churnRiskLevel &lt;&gt; 'LOW'
             ORDER BY CASE churnRiskLevel WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                      inactiveDays DESC, unpaidAmount DESC, salesAmount DESC, customerCode
             LIMIT 80
            </script>
            """)
    List<Map<String, Object>> customerChurnRiskRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT COUNT(*) AS paymentCount,
                   COALESCE(SUM(p.paid_amount), 0) AS receiptAmount,
                   MAX(p.source_updated_time) AS latestUpdatedTime
              FROM bi_sales_payment_fact p
             WHERE p.tenant_id = #{tenantId}
               AND p.deleted = 0
               AND p.payment_time &gt;= #{from}
               AND p.payment_time &lt;= #{to}
            <if test="regionCode != null">
               AND p.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND (p.owner_staff_code = #{ownerStaffCode} OR p.collector_staff_code = #{ownerStaffCode})
            </if>
            <if test="customerTypeCode != null">
               AND p.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND p.source_system_code = #{sourceSystemCode}
            </if>
            </script>
            """)
    Map<String, Object> collectionSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT COUNT(*) AS recordCount,
                   COALESCE(SUM(cost_amount), 0) AS costAmount,
                   COALESCE(SUM(budget_amount), 0) AS budgetAmount,
                   MAX(updated_time) AS latestUpdatedTime
              FROM bi_city_cost_record
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
               AND cost_date &gt;= #{from}
               AND cost_date &lt;= #{to}
            <if test="regionCode != null">
               AND region_code = #{regionCode}
            </if>
            </script>
            """)
    Map<String, Object> cityCostSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode);

    @Select("""
            <script>
            SELECT COALESCE(SUM(l.line_amount), 0) AS salesAmount,
                   COALESCE(SUM(l.discount_amount), 0) AS discountAmount,
                   COALESCE(SUM(l.refund_amount), 0) AS refundAmount,
                   COALESCE(SUM(l.sales_net_amount), 0) AS salesNetAmount,
                   COALESCE(SUM(l.estimated_cost_amount), 0) AS estimatedCostAmount,
                   COALESCE(SUM(l.estimated_gross_profit_amount), 0) AS estimatedGrossProfit,
                   CASE WHEN COALESCE(SUM(l.sales_net_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(l.estimated_gross_profit_amount), 0) / COALESCE(SUM(l.sales_net_amount), 0) * 100 END
                        AS estimatedGrossProfitRate,
                   CASE WHEN COALESCE(SUM(l.line_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(CASE WHEN l.cost_covered = 1 THEN l.line_amount ELSE 0 END), 0)
                             / COALESCE(SUM(l.line_amount), 0) * 100 END AS costCoverageRate,
                   MAX(l.source_updated_time) AS latestUpdatedTime
              FROM bi_sales_order_line_fact l
             WHERE l.tenant_id = #{tenantId}
               AND l.deleted = 0
               AND l.order_status_code &lt;&gt; 'CANCELLED'
               AND l.order_date &gt;= #{from}
               AND l.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND l.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND l.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND l.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
               AND l.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
               AND l.source_system_code = #{sourceSystemCode}
            </if>
            </script>
            """)
    Map<String, Object> profitSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT COALESCE(SUM(o.unpaid_amount), 0) AS riskAmount,
                   COUNT(DISTINCT CASE WHEN o.unpaid_amount &gt; 0 THEN o.customer_id END) AS riskCustomerCount,
                   COUNT(DISTINCT CASE
                       WHEN o.unpaid_amount &gt; 0
                            AND GREATEST(DATEDIFF(#{to}, o.payment_due_date), 0) &gt; 0
                            AND CASE WHEN COALESCE(o.payable_amount, 0) = 0 THEN 0
                                     ELSE COALESCE(o.paid_amount, 0) / COALESCE(o.payable_amount, 0) * 100 END &lt;= 20
                       THEN o.customer_id END) AS highRiskCustomerCount,
                   COALESCE(AVG(CASE WHEN o.unpaid_amount &gt; 0 THEN GREATEST(DATEDIFF(#{to}, o.payment_due_date), 0) END), 0)
                       AS averageOverdueDays,
                   CASE WHEN COALESCE(MAX(total_sales.salesAmount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(o.unpaid_amount), 0) / COALESCE(MAX(total_sales.salesAmount), 0) * 100 END
                        AS riskAmountRate,
                   MAX(o.source_updated_time) AS latestUpdatedTime
              FROM bi_sales_order_fact o
              LEFT JOIN (
                    SELECT COALESCE(SUM(payable_amount), 0) AS salesAmount
                      FROM bi_sales_order_fact
                     WHERE tenant_id = #{tenantId}
                       AND deleted = 0
                       AND order_status_code &lt;&gt; 'CANCELLED'
                       AND order_date &gt;= #{from}
                       AND order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND source_system_code = #{sourceSystemCode}
            </if>
              ) total_sales ON 1 = 1
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.unpaid_amount &gt; 0
               AND o.payment_due_date &lt; #{to}
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND o.source_system_code = #{sourceSystemCode}
            </if>
            </script>
            """)
    Map<String, Object> paymentRiskSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'sales_amount' AS metricCode,
                   DATE_FORMAT(o.order_date, '%Y-%m-%d') AS period,
                   COALESCE(SUM(o.payable_amount), 0) AS value,
                   COALESCE(SUM(o.paid_amount), 0) AS secondaryValue
              FROM bi_sales_order_fact o
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                   AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                   AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                   AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                   AND o.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY DATE_FORMAT(o.order_date, '%Y-%m-%d')
             ORDER BY period
            </script>
            """)
    List<Map<String, Object>> salesTrend(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'receipt_amount' AS metricCode,
                   DATE_FORMAT(p.payment_time, '%Y-%m-%d') AS period,
                   COALESCE(SUM(p.paid_amount), 0) AS value,
                   COUNT(*) AS secondaryValue
              FROM bi_sales_payment_fact p
             WHERE p.tenant_id = #{tenantId}
               AND p.deleted = 0
               AND p.payment_time &gt;= #{from}
               AND p.payment_time &lt;= #{to}
            <if test="regionCode != null">
               AND p.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND (p.owner_staff_code = #{ownerStaffCode} OR p.collector_staff_code = #{ownerStaffCode})
            </if>
            <if test="customerTypeCode != null">
               AND p.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND p.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY DATE_FORMAT(p.payment_time, '%Y-%m-%d')
             ORDER BY period
            </script>
            """)
    List<Map<String, Object>> collectionTrend(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'city_cost_amount' AS metricCode,
                   DATE_FORMAT(cost_date, '%Y-%m-%d') AS period,
                   COALESCE(SUM(cost_amount), 0) AS value,
                   COALESCE(SUM(budget_amount), 0) AS secondaryValue
              FROM bi_city_cost_record
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
               AND cost_date &gt;= #{from}
               AND cost_date &lt;= #{to}
            <if test="regionCode != null">
               AND region_code = #{regionCode}
            </if>
             GROUP BY DATE_FORMAT(cost_date, '%Y-%m-%d')
             ORDER BY period
            </script>
            """)
    List<Map<String, Object>> cityCostTrend(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode);

    @Select("""
            <script>
            SELECT 'CITY_SALES' AS rankType,
                   COALESCE(o.region_code, 'UNKNOWN') AS dimensionCode,
                   COALESCE(MAX(NULLIF(o.region_name, '')), MAX(COALESCE(o.region_code, 'UNKNOWN'))) AS dimensionName,
                   COALESCE(o.region_code, 'UNKNOWN') AS regionCode,
                   COALESCE(MAX(NULLIF(o.region_name, '')), MAX(COALESCE(o.region_code, 'UNKNOWN'))) AS regionName,
                   COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                   COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                   COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                   COUNT(*) AS orderCount,
                   COUNT(DISTINCT o.customer_id) AS customerCount,
                   CASE WHEN COALESCE(SUM(o.payable_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(o.paid_amount), 0) / COALESCE(SUM(o.payable_amount), 0) * 100 END AS rate
              FROM bi_sales_order_fact o
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND o.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY COALESCE(o.region_code, 'UNKNOWN')
             ORDER BY salesAmount DESC, orderCount DESC
            </script>
            """)
    List<Map<String, Object>> citySalesRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'CITY_COLLECTION_RATE' AS rankType,
                   COALESCE(o.region_code, 'UNKNOWN') AS dimensionCode,
                   COALESCE(MAX(NULLIF(o.region_name, '')), MAX(COALESCE(o.region_code, 'UNKNOWN'))) AS dimensionName,
                   COALESCE(o.region_code, 'UNKNOWN') AS regionCode,
                   COALESCE(MAX(NULLIF(o.region_name, '')), MAX(COALESCE(o.region_code, 'UNKNOWN'))) AS regionName,
                   COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                   COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                   COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                   COUNT(*) AS orderCount,
                   COUNT(DISTINCT o.customer_id) AS customerCount,
                   CASE WHEN COALESCE(SUM(o.payable_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(o.paid_amount), 0) / COALESCE(SUM(o.payable_amount), 0) * 100 END AS rate
              FROM bi_sales_order_fact o
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND o.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY COALESCE(o.region_code, 'UNKNOWN')
            HAVING salesAmount &gt; 0
             ORDER BY rate DESC, paidAmount DESC, salesAmount DESC
             LIMIT 50
            </script>
            """)
    List<Map<String, Object>> cityCollectionRateRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'SALES_OWNER' AS rankType,
                   COALESCE(o.owner_staff_code, 'UNKNOWN') AS dimensionCode,
                   COALESCE(MAX(o.owner_staff_name), MAX(o.owner_staff_code), '未分配销售') AS dimensionName,
                   CASE WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) = 1 THEN MAX(NULLIF(o.region_code, ''))
                        WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) &gt; 1 THEN 'MULTI'
                        ELSE NULL END AS regionCode,
                   CASE WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) = 1
                        THEN COALESCE(MAX(NULLIF(o.region_name, '')), MAX(NULLIF(o.region_code, '')))
                        WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) &gt; 1 THEN '多城市'
                        ELSE NULL END AS regionName,
                   COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                   COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                   COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                   COUNT(*) AS orderCount,
                   COUNT(DISTINCT o.customer_id) AS customerCount,
                   CASE WHEN COALESCE(SUM(o.payable_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(o.paid_amount), 0) / COALESCE(SUM(o.payable_amount), 0) * 100 END AS rate
              FROM bi_sales_order_fact o
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND o.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY COALESCE(o.owner_staff_code, 'UNKNOWN')
             ORDER BY salesAmount DESC, orderCount DESC
            </script>
            """)
    List<Map<String, Object>> salesRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT DATE_FORMAT(o.order_date, '%Y-%m') AS period,
                   COALESCE(o.owner_staff_code, 'UNKNOWN') AS ownerStaffCode,
                   COALESCE(MAX(o.owner_staff_name), MAX(o.owner_staff_code), '未分配销售') AS ownerStaffName,
                   CASE WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) = 1 THEN MAX(NULLIF(o.region_code, ''))
                        WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) &gt; 1 THEN 'MULTI'
                        ELSE NULL END AS regionCode,
                   CASE WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) = 1
                        THEN COALESCE(MAX(NULLIF(o.region_name, '')), MAX(NULLIF(o.region_code, '')))
                        WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) &gt; 1 THEN '多城市'
                        ELSE NULL END AS regionName,
                   COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                   COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                   COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                   COUNT(*) AS orderCount,
                   COUNT(DISTINCT o.customer_id) AS customerCount,
                   CASE WHEN COALESCE(SUM(o.payable_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(o.paid_amount), 0) / COALESCE(SUM(o.payable_amount), 0) * 100 END AS rate
              FROM bi_sales_order_fact o
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                   AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                   AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                   AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                   AND o.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY DATE_FORMAT(o.order_date, '%Y-%m'), COALESCE(o.owner_staff_code, 'UNKNOWN')
             ORDER BY period, salesAmount DESC, orderCount DESC
             LIMIT 300
            </script>
            """)
    List<Map<String, Object>> salesMonthlyPerformance(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'SOURCE_SYSTEM' AS rankType,
                   sourceCode AS dimensionCode,
                   CASE sourceCode
                       WHEN 'DINGHUOBAO' THEN '订货宝'
                       WHEN 'MANUAL' THEN '手工订单'
                       ELSE sourceCode
                   END AS dimensionName,
                   NULL AS regionCode,
                   NULL AS regionName,
                   salesAmount,
                   paidAmount,
                   unpaidAmount,
                   orderCount,
                   customerCount,
                   CASE WHEN salesAmount = 0 THEN 0 ELSE paidAmount / salesAmount * 100 END AS rate
              FROM (
                    SELECT COALESCE(o.source_system_code, 'UNKNOWN') AS sourceCode,
                           COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                           COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                           COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                           COUNT(*) AS orderCount,
                           COUNT(DISTINCT o.customer_id) AS customerCount
                      FROM bi_sales_order_fact o
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND o.source_system_code = #{sourceSystemCode}
            </if>
                     GROUP BY COALESCE(o.source_system_code, 'UNKNOWN')
              ) source
             ORDER BY salesAmount DESC, orderCount DESC
             LIMIT 20
            </script>
            """)
    List<Map<String, Object>> sourceSystemBreakdown(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'PAYMENT_RISK_CITY' AS rankType,
                   COALESCE(o.region_code, 'UNKNOWN') AS dimensionCode,
                   COALESCE(MAX(NULLIF(o.region_name, '')), MAX(COALESCE(o.region_code, 'UNKNOWN'))) AS dimensionName,
                   COALESCE(o.region_code, 'UNKNOWN') AS regionCode,
                   COALESCE(MAX(NULLIF(o.region_name, '')), MAX(COALESCE(o.region_code, 'UNKNOWN'))) AS regionName,
                   COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                   COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                   COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                   COUNT(*) AS orderCount,
                   COUNT(DISTINCT o.customer_id) AS customerCount,
                   CASE WHEN COALESCE(SUM(o.payable_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(o.paid_amount), 0) / COALESCE(SUM(o.payable_amount), 0) * 100 END AS rate
              FROM bi_sales_order_fact o
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.unpaid_amount &gt; 0
               AND o.payment_due_date &lt; #{to}
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND o.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY COALESCE(o.region_code, 'UNKNOWN')
             ORDER BY unpaidAmount DESC, rate ASC
             LIMIT 20
            </script>
            """)
    List<Map<String, Object>> paymentRiskCityRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'PAYMENT_RISK_OWNER' AS rankType,
                   COALESCE(o.owner_staff_code, 'UNKNOWN') AS dimensionCode,
                   COALESCE(MAX(o.owner_staff_name), MAX(o.owner_staff_code), '未分配销售') AS dimensionName,
                   CASE WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) = 1 THEN MAX(NULLIF(o.region_code, ''))
                        WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) > 1 THEN 'MULTI'
                        ELSE NULL END AS regionCode,
                   CASE WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) = 1
                        THEN COALESCE(MAX(NULLIF(o.region_name, '')), MAX(NULLIF(o.region_code, '')))
                        WHEN COUNT(DISTINCT NULLIF(o.region_code, '')) > 1 THEN '多城市'
                        ELSE NULL END AS regionName,
                   COALESCE(SUM(o.payable_amount), 0) AS salesAmount,
                   COALESCE(SUM(o.paid_amount), 0) AS paidAmount,
                   COALESCE(SUM(o.unpaid_amount), 0) AS unpaidAmount,
                   COUNT(*) AS orderCount,
                   COUNT(DISTINCT o.customer_id) AS customerCount,
                   CASE WHEN COALESCE(SUM(o.payable_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(o.paid_amount), 0) / COALESCE(SUM(o.payable_amount), 0) * 100 END AS rate
              FROM bi_sales_order_fact o
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
               AND o.order_status_code &lt;&gt; 'CANCELLED'
               AND o.unpaid_amount &gt; 0
               AND o.payment_due_date &lt; #{to}
               AND o.order_date &gt;= #{from}
               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND o.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY COALESCE(o.owner_staff_code, 'UNKNOWN')
             ORDER BY unpaidAmount DESC, customerCount DESC
            </script>
            """)
    List<Map<String, Object>> paymentRiskSalesRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT bucketCode,
                   bucketName,
                   COUNT(*) AS orderCount,
                   COUNT(DISTINCT customerId) AS customerCount,
                   COALESCE(SUM(unpaidAmount), 0) AS unpaidAmount
              FROM (
                    SELECT o.order_id AS orderId,
                           o.customer_id AS customerId,
                           o.unpaid_amount AS unpaidAmount,
                           CASE
                               WHEN o.payment_due_date IS NULL OR o.payment_due_date &gt;= #{to} THEN 'CURRENT'
                               WHEN DATEDIFF(#{to}, o.payment_due_date) BETWEEN 1 AND 30 THEN 'DAYS_1_30'
                               WHEN DATEDIFF(#{to}, o.payment_due_date) BETWEEN 31 AND 60 THEN 'DAYS_31_60'
                               ELSE 'DAYS_61_PLUS'
                           END AS bucketCode,
                           CASE
                               WHEN o.payment_due_date IS NULL OR o.payment_due_date &gt;= #{to} THEN '未逾期'
                               WHEN DATEDIFF(#{to}, o.payment_due_date) BETWEEN 1 AND 30 THEN '逾期1-30天'
                               WHEN DATEDIFF(#{to}, o.payment_due_date) BETWEEN 31 AND 60 THEN '逾期31-60天'
                               ELSE '逾期60天以上'
                           END AS bucketName,
                           CASE
                               WHEN o.payment_due_date IS NULL OR o.payment_due_date &gt;= #{to} THEN 0
                               WHEN DATEDIFF(#{to}, o.payment_due_date) BETWEEN 1 AND 30 THEN 1
                               WHEN DATEDIFF(#{to}, o.payment_due_date) BETWEEN 31 AND 60 THEN 2
                               ELSE 3
                           END AS bucketSort
                      FROM bi_sales_order_fact o
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.unpaid_amount &gt; 0
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND o.source_system_code = #{sourceSystemCode}
            </if>
              ) bucketed_orders
             GROUP BY bucketCode, bucketName, bucketSort
             ORDER BY bucketSort
            </script>
            """)
    List<Map<String, Object>> paymentAgingBuckets(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'PRODUCT' AS rankType,
                   COALESCE(CAST(p.product_id AS CHAR), p.product_code, 'UNKNOWN') AS dimensionCode,
                   COALESCE(p.product_name, p.product_code, '未知商品') AS dimensionName,
                   COALESCE(CAST(p.product_category_id AS CHAR), 'UNKNOWN') AS categoryCode,
                   COALESCE(p.product_category_name, CAST(p.product_category_id AS CHAR), '未分配分类') AS categoryName,
                   COALESCE(SUM(l.quantity), 0) AS salesQuantity,
                   COALESCE(SUM(l.line_amount), 0) AS salesAmount,
                   COALESCE(SUM(l.discount_amount), 0) AS discountAmount,
                   COALESCE(SUM(l.refund_amount), 0) AS refundAmount,
                   COALESCE(SUM(l.sales_net_amount), 0) AS salesNetAmount,
                   COALESCE(SUM(l.estimated_cost_amount), 0) AS estimatedCostAmount,
                   COALESCE(SUM(l.estimated_gross_profit_amount), 0) AS estimatedGrossProfit,
                   CASE WHEN COALESCE(SUM(l.sales_net_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(l.estimated_gross_profit_amount), 0) / COALESCE(SUM(l.sales_net_amount), 0) * 100 END
                        AS estimatedGrossProfitRate,
                   CASE WHEN COALESCE(SUM(l.line_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(CASE WHEN l.cost_covered = 1 THEN l.line_amount ELSE 0 END), 0)
                             / COALESCE(SUM(l.line_amount), 0) * 100 END AS costCoverageRate,
                   COUNT(DISTINCT l.order_id) AS orderCount,
                   COUNT(DISTINCT l.customer_id) AS customerCount
              FROM bi_product_dim p
              LEFT JOIN bi_sales_order_line_fact l
                ON l.tenant_id = p.tenant_id
               AND l.product_id = p.product_id
               AND l.deleted = 0
               AND l.order_status_code &lt;&gt; 'CANCELLED'
               AND l.order_date &gt;= #{from}
               AND l.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND l.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND l.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND l.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
               AND l.source_system_code = #{sourceSystemCode}
            </if>
             WHERE p.tenant_id = #{tenantId}
               AND p.deleted = 0
            <if test="productCategoryId != null">
               AND p.product_category_id = #{productCategoryId}
            </if>
             GROUP BY p.product_id, p.product_code, p.product_name,
                      p.product_category_id, p.product_category_code, p.product_category_name
             ORDER BY salesAmount DESC, salesQuantity DESC, p.product_id
             LIMIT 500
            </script>
            """)
    List<Map<String, Object>> productSalesRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'SKU' AS rankType,
                   COALESCE(CAST(l.product_variant_id AS CHAR), l.sku_code, l.product_code, 'UNKNOWN') AS dimensionCode,
                   COALESCE(
                       CONCAT(
                           COALESCE(MAX(l.product_name), MAX(l.product_code), '未知商品'),
                           CASE WHEN MAX(NULLIF(l.specification_snapshot, '')) IS NULL THEN ''
                                ELSE CONCAT(' / ', MAX(NULLIF(l.specification_snapshot, ''))) END
                       ),
                       MAX(l.sku_code),
                       MAX(l.product_code),
                       '未知SKU'
                   ) AS dimensionName,
                   COALESCE(CAST(MAX(l.product_category_id) AS CHAR), 'UNKNOWN') AS categoryCode,
                   COALESCE(MAX(l.product_category_name), CAST(MAX(l.product_category_id) AS CHAR), '未分配分类') AS categoryName,
                   COALESCE(SUM(l.quantity), 0) AS salesQuantity,
                   COALESCE(SUM(l.line_amount), 0) AS salesAmount,
                   COALESCE(SUM(l.discount_amount), 0) AS discountAmount,
                   COALESCE(SUM(l.refund_amount), 0) AS refundAmount,
                   COALESCE(SUM(l.sales_net_amount), 0) AS salesNetAmount,
                   COALESCE(SUM(l.estimated_cost_amount), 0) AS estimatedCostAmount,
                   COALESCE(SUM(l.estimated_gross_profit_amount), 0) AS estimatedGrossProfit,
                   CASE WHEN COALESCE(SUM(l.sales_net_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(l.estimated_gross_profit_amount), 0) / COALESCE(SUM(l.sales_net_amount), 0) * 100 END
                        AS estimatedGrossProfitRate,
                   CASE WHEN COALESCE(SUM(l.line_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(CASE WHEN l.cost_covered = 1 THEN l.line_amount ELSE 0 END), 0)
                             / COALESCE(SUM(l.line_amount), 0) * 100 END AS costCoverageRate,
                   COUNT(DISTINCT l.order_id) AS orderCount,
                   COUNT(DISTINCT l.customer_id) AS customerCount
              FROM bi_sales_order_line_fact l
             WHERE l.tenant_id = #{tenantId}
               AND l.deleted = 0
               AND l.order_status_code &lt;&gt; 'CANCELLED'
               AND l.order_date &gt;= #{from}
               AND l.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND l.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND l.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND l.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
               AND l.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
               AND l.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY COALESCE(CAST(l.product_variant_id AS CHAR), l.sku_code, l.product_code, 'UNKNOWN')
             ORDER BY salesAmount DESC, salesQuantity DESC
             LIMIT 80
            </script>
            """)
    List<Map<String, Object>> skuSalesRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'CATEGORY' AS rankType,
                   COALESCE(CAST(MAX(l.product_category_id) AS CHAR), 'UNKNOWN') AS dimensionCode,
                   COALESCE(MAX(l.product_category_name), CAST(MAX(l.product_category_id) AS CHAR), '未分配分类') AS dimensionName,
                   COALESCE(CAST(MAX(l.product_category_id) AS CHAR), 'UNKNOWN') AS categoryCode,
                   COALESCE(MAX(l.product_category_name), CAST(MAX(l.product_category_id) AS CHAR), '未分配分类') AS categoryName,
                   COALESCE(SUM(l.quantity), 0) AS salesQuantity,
                   COALESCE(SUM(l.line_amount), 0) AS salesAmount,
                   COALESCE(SUM(l.discount_amount), 0) AS discountAmount,
                   COALESCE(SUM(l.refund_amount), 0) AS refundAmount,
                   COALESCE(SUM(l.sales_net_amount), 0) AS salesNetAmount,
                   COALESCE(SUM(l.estimated_cost_amount), 0) AS estimatedCostAmount,
                   COALESCE(SUM(l.estimated_gross_profit_amount), 0) AS estimatedGrossProfit,
                   CASE WHEN COALESCE(SUM(l.sales_net_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(l.estimated_gross_profit_amount), 0) / COALESCE(SUM(l.sales_net_amount), 0) * 100 END
                        AS estimatedGrossProfitRate,
                   CASE WHEN COALESCE(SUM(l.line_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(CASE WHEN l.cost_covered = 1 THEN l.line_amount ELSE 0 END), 0)
                             / COALESCE(SUM(l.line_amount), 0) * 100 END AS costCoverageRate,
                   COUNT(DISTINCT l.order_id) AS orderCount,
                   COUNT(DISTINCT l.customer_id) AS customerCount
              FROM bi_sales_order_line_fact l
             WHERE l.tenant_id = #{tenantId}
               AND l.deleted = 0
               AND l.order_status_code &lt;&gt; 'CANCELLED'
               AND l.order_date &gt;= #{from}
               AND l.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND l.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND l.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND l.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
               AND l.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
               AND l.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY COALESCE(CAST(l.product_category_id AS CHAR), 'UNKNOWN')
             ORDER BY salesAmount DESC, salesQuantity DESC
             LIMIT 20
            </script>
            """)
    List<Map<String, Object>> categorySalesRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'BRAND' AS rankType,
                   COALESCE(CAST(l.brand_id AS CHAR), l.brand_code, 'UNKNOWN') AS dimensionCode,
                   COALESCE(MAX(l.brand_name), MAX(l.brand_code), '未分配品牌') AS dimensionName,
                   COALESCE(CAST(MAX(l.product_category_id) AS CHAR), 'UNKNOWN') AS categoryCode,
                   COALESCE(MAX(l.product_category_name), CAST(MAX(l.product_category_id) AS CHAR), '未分配分类') AS categoryName,
                   COALESCE(SUM(l.quantity), 0) AS salesQuantity,
                   COALESCE(SUM(l.line_amount), 0) AS salesAmount,
                   COALESCE(SUM(l.discount_amount), 0) AS discountAmount,
                   COALESCE(SUM(l.refund_amount), 0) AS refundAmount,
                   COALESCE(SUM(l.sales_net_amount), 0) AS salesNetAmount,
                   COALESCE(SUM(l.estimated_cost_amount), 0) AS estimatedCostAmount,
                   COALESCE(SUM(l.estimated_gross_profit_amount), 0) AS estimatedGrossProfit,
                   CASE WHEN COALESCE(SUM(l.sales_net_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(l.estimated_gross_profit_amount), 0) / COALESCE(SUM(l.sales_net_amount), 0) * 100 END
                        AS estimatedGrossProfitRate,
                   CASE WHEN COALESCE(SUM(l.line_amount), 0) = 0 THEN 0
                        ELSE COALESCE(SUM(CASE WHEN l.cost_covered = 1 THEN l.line_amount ELSE 0 END), 0)
                             / COALESCE(SUM(l.line_amount), 0) * 100 END AS costCoverageRate,
                   COUNT(DISTINCT l.order_id) AS orderCount,
                   COUNT(DISTINCT l.customer_id) AS customerCount
              FROM bi_sales_order_line_fact l
             WHERE l.tenant_id = #{tenantId}
               AND l.deleted = 0
               AND l.order_status_code &lt;&gt; 'CANCELLED'
               AND l.order_date &gt;= #{from}
               AND l.order_date &lt;= #{to}
            <if test="regionCode != null">
               AND l.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
               AND l.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
               AND l.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
               AND l.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
               AND l.source_system_code = #{sourceSystemCode}
            </if>
             GROUP BY COALESCE(CAST(l.brand_id AS CHAR), l.brand_code, 'UNKNOWN')
             ORDER BY salesAmount DESC, salesQuantity DESC
             LIMIT 20
            </script>
            """)
    List<Map<String, Object>> brandSalesRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT t.dimension_type AS dimensionType,
                   t.dimension_code AS dimensionCode,
                   COALESCE(MAX(NULLIF(t.dimension_name, '')), t.dimension_code) AS dimensionName,
                   t.metric_code AS metricCode,
                   CASE t.metric_code
                       WHEN 'CONTACTED_CUSTOMER' THEN '建联客户数'
                       WHEN 'COOPERATED_CUSTOMER' THEN '合作客户数'
                       WHEN 'SALES_AMOUNT' THEN '销售额'
                       WHEN 'PAID_AMOUNT' THEN '回款额'
                       ELSE t.metric_code
                   END AS metricName,
                   COALESCE(SUM(t.target_value), 0) AS targetValue,
                   COALESCE(MAX(actuals.actualValue), 0) AS actualValue,
                   CASE WHEN COALESCE(SUM(t.target_value), 0) = 0 THEN 0
                        ELSE COALESCE(MAX(actuals.actualValue), 0) / COALESCE(SUM(t.target_value), 0) * 100 END
                        AS achievementRate
              FROM bi_business_target t
              LEFT JOIN (
                    SELECT metricCode, dimensionCode, COALESCE(SUM(actualValue), 0) AS actualValue
                      FROM (
                            SELECT 'CONTACTED_CUSTOMER' AS metricCode,
                                   COALESCE(c.region_code, 'UNKNOWN') AS dimensionCode,
                                   COUNT(*) AS actualValue
                              FROM bi_customer_dim c
                             WHERE c.tenant_id = #{tenantId}
                               AND c.deleted = 0
                               AND c.status_code = 'ACTIVE'
                               AND c.has_contact = 1
            <if test="regionCode != null">
                               AND c.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                               AND c.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                               AND c.customer_type_code = #{customerTypeCode}
            </if>
                             GROUP BY COALESCE(c.region_code, 'UNKNOWN')
                            UNION ALL
                            SELECT 'COOPERATED_CUSTOMER',
                                   COALESCE(o.region_code, 'UNKNOWN'),
                                   COUNT(DISTINCT o.customer_id)
                              FROM bi_sales_order_fact o
                             WHERE o.tenant_id = #{tenantId}
                               AND o.deleted = 0
                               AND o.order_status_code &lt;&gt; 'CANCELLED'
                               AND o.order_date &gt;= #{from}
                               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                               AND o.source_system_code = #{sourceSystemCode}
            </if>
                             GROUP BY COALESCE(o.region_code, 'UNKNOWN')
                            UNION ALL
                            SELECT 'SALES_AMOUNT',
                                   COALESCE(o.region_code, 'UNKNOWN'),
                                   COALESCE(SUM(o.payable_amount), 0)
                              FROM bi_sales_order_fact o
                             WHERE o.tenant_id = #{tenantId}
                               AND o.deleted = 0
                               AND o.order_status_code &lt;&gt; 'CANCELLED'
                               AND o.order_date &gt;= #{from}
                               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                               AND o.source_system_code = #{sourceSystemCode}
            </if>
                             GROUP BY COALESCE(o.region_code, 'UNKNOWN')
                            UNION ALL
                            SELECT 'PAID_AMOUNT',
                                   COALESCE(o.region_code, 'UNKNOWN'),
                                   COALESCE(SUM(o.paid_amount), 0)
                              FROM bi_sales_order_fact o
                             WHERE o.tenant_id = #{tenantId}
                               AND o.deleted = 0
                               AND o.order_status_code &lt;&gt; 'CANCELLED'
                               AND o.order_date &gt;= #{from}
                               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                               AND o.source_system_code = #{sourceSystemCode}
            </if>
                             GROUP BY COALESCE(o.region_code, 'UNKNOWN')
                      ) actual_rows
                     GROUP BY metricCode, dimensionCode
              ) actuals ON actuals.metricCode = t.metric_code AND actuals.dimensionCode = t.dimension_code
             WHERE t.tenant_id = #{tenantId}
               AND t.deleted = 0
               AND t.dimension_type = 'CITY'
               AND t.target_month &gt;= DATE_FORMAT(#{from}, '%Y-%m-01')
               AND t.target_month &lt;= DATE_FORMAT(#{to}, '%Y-%m-01')
            <if test="regionCode != null">
               AND t.dimension_code = #{regionCode}
            </if>
             GROUP BY t.dimension_type, t.dimension_code, t.metric_code
             ORDER BY achievementRate ASC, targetValue DESC
             LIMIT 80
            </script>
            """)
    List<Map<String, Object>> cityTargetCompletions(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT t.dimension_type AS dimensionType,
                   t.dimension_code AS dimensionCode,
                   COALESCE(MAX(NULLIF(t.dimension_name, '')), t.dimension_code) AS dimensionName,
                   t.metric_code AS metricCode,
                   CASE t.metric_code
                       WHEN 'SALES_AMOUNT' THEN '销售额'
                       WHEN 'PAID_AMOUNT' THEN '回款额'
                       WHEN 'COOPERATED_CUSTOMER' THEN '合作客户数'
                       ELSE t.metric_code
                   END AS metricName,
                   COALESCE(SUM(t.target_value), 0) AS targetValue,
                   COALESCE(MAX(actuals.actualValue), 0) AS actualValue,
                   CASE WHEN COALESCE(SUM(t.target_value), 0) = 0 THEN 0
                        ELSE COALESCE(MAX(actuals.actualValue), 0) / COALESCE(SUM(t.target_value), 0) * 100 END
                        AS achievementRate
              FROM bi_business_target t
              LEFT JOIN (
                    SELECT metricCode, dimensionCode, COALESCE(SUM(actualValue), 0) AS actualValue
                      FROM (
                            SELECT 'COOPERATED_CUSTOMER' AS metricCode,
                                   COALESCE(o.owner_staff_code, 'UNKNOWN') AS dimensionCode,
                                   COUNT(DISTINCT o.customer_id) AS actualValue
                              FROM bi_sales_order_fact o
                             WHERE o.tenant_id = #{tenantId}
                               AND o.deleted = 0
                               AND o.order_status_code &lt;&gt; 'CANCELLED'
                               AND o.order_date &gt;= #{from}
                               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                               AND o.source_system_code = #{sourceSystemCode}
            </if>
                             GROUP BY COALESCE(o.owner_staff_code, 'UNKNOWN')
                            UNION ALL
                            SELECT 'SALES_AMOUNT',
                                   COALESCE(o.owner_staff_code, 'UNKNOWN'),
                                   COALESCE(SUM(o.payable_amount), 0)
                              FROM bi_sales_order_fact o
                             WHERE o.tenant_id = #{tenantId}
                               AND o.deleted = 0
                               AND o.order_status_code &lt;&gt; 'CANCELLED'
                               AND o.order_date &gt;= #{from}
                               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                               AND o.source_system_code = #{sourceSystemCode}
            </if>
                             GROUP BY COALESCE(o.owner_staff_code, 'UNKNOWN')
                            UNION ALL
                            SELECT 'PAID_AMOUNT',
                                   COALESCE(o.owner_staff_code, 'UNKNOWN'),
                                   COALESCE(SUM(o.paid_amount), 0)
                              FROM bi_sales_order_fact o
                             WHERE o.tenant_id = #{tenantId}
                               AND o.deleted = 0
                               AND o.order_status_code &lt;&gt; 'CANCELLED'
                               AND o.order_date &gt;= #{from}
                               AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                               AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                               AND o.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                               AND o.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                               AND o.source_system_code = #{sourceSystemCode}
            </if>
                             GROUP BY COALESCE(o.owner_staff_code, 'UNKNOWN')
                      ) actual_rows
                     GROUP BY metricCode, dimensionCode
              ) actuals ON actuals.metricCode = t.metric_code AND actuals.dimensionCode = t.dimension_code
             WHERE t.tenant_id = #{tenantId}
               AND t.deleted = 0
               AND t.dimension_type = 'SALES_OWNER'
               AND t.target_month &gt;= DATE_FORMAT(#{from}, '%Y-%m-01')
               AND t.target_month &lt;= DATE_FORMAT(#{to}, '%Y-%m-01')
            <if test="ownerStaffCode != null">
               AND t.dimension_code = #{ownerStaffCode}
            </if>
             GROUP BY t.dimension_type, t.dimension_code, t.metric_code
             ORDER BY achievementRate ASC, targetValue DESC
             LIMIT 80
            </script>
            """)
    List<Map<String, Object>> salesTargetCompletions(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT categoryCode,
                   categoryName,
                   unitCode,
                   COALESCE(SUM(procurementQuantity), 0) AS procurementQuantity,
                   COALESCE(SUM(shippedQuantity), 0) AS shippedQuantity,
                   COALESCE(SUM(remainingQuantity), 0) AS remainingQuantity,
                   COALESCE(SUM(inactiveRemainingQuantity), 0) AS inactiveRemainingQuantity
              FROM (
                    SELECT COALESCE(product_category_code, CAST(product_category_id AS CHAR), 'UNKNOWN') AS categoryCode,
                           COALESCE(product_category_name, CAST(product_category_id AS CHAR), '未分配分类') AS categoryName,
                           COALESCE(unit_code, 'UNKNOWN') AS unitCode,
                           COALESCE(SUM(CASE WHEN operation_type = 'PROCUREMENT' THEN quantity ELSE 0 END), 0)
                                AS procurementQuantity,
                           COALESCE(SUM(CASE WHEN operation_type = 'SALES_SHIPMENT' THEN quantity ELSE 0 END), 0)
                                AS shippedQuantity,
                           0 AS remainingQuantity,
                           0 AS inactiveRemainingQuantity
                      FROM bi_inventory_operation_fact
                     WHERE tenant_id = #{tenantId}
                       AND deleted = 0
                       AND operation_time &gt;= #{from}
                       AND operation_time &lt;= #{to}
            <if test="productCategoryId != null">
                       AND product_category_id = #{productCategoryId}
            </if>
                     GROUP BY COALESCE(product_category_code, CAST(product_category_id AS CHAR), 'UNKNOWN'),
                              COALESCE(product_category_name, CAST(product_category_id AS CHAR), '未分配分类'),
                              COALESCE(unit_code, 'UNKNOWN')
                    UNION ALL
                    SELECT COALESCE(p.product_category_code, CAST(b.product_category_id AS CHAR), 'UNKNOWN') AS categoryCode,
                           COALESCE(p.product_category_name, CAST(b.product_category_id AS CHAR), '未分配分类') AS categoryName,
                           COALESCE(b.unit_code, 'UNKNOWN') AS unitCode,
                           0 AS procurementQuantity,
                           0 AS shippedQuantity,
                           COALESCE(SUM(b.available_quantity), 0) AS remainingQuantity,
                           COALESCE(SUM(CASE WHEN b.deleted &lt;&gt; 0 THEN b.available_quantity ELSE 0 END), 0)
                                AS inactiveRemainingQuantity
                      FROM bi_inventory_balance_current b
                      LEFT JOIN bi_product_dim p
                        ON p.tenant_id = b.tenant_id
                       AND p.product_id = b.product_id
                     WHERE b.tenant_id = #{tenantId}
                       AND (
                            b.deleted = 0
                            OR b.available_quantity &lt;&gt; 0
                            OR b.locked_quantity &lt;&gt; 0
                            OR b.in_transit_quantity &lt;&gt; 0
                       )
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
                     GROUP BY COALESCE(p.product_category_code, CAST(b.product_category_id AS CHAR), 'UNKNOWN'),
                              COALESCE(p.product_category_name, CAST(b.product_category_id AS CHAR), '未分配分类'),
                              COALESCE(b.unit_code, 'UNKNOWN')
              ) inventory_rows
             GROUP BY categoryCode, categoryName, unitCode
            HAVING procurementQuantity &lt;&gt; 0 OR shippedQuantity &lt;&gt; 0 OR remainingQuantity &lt;&gt; 0
                OR inactiveRemainingQuantity &lt;&gt; 0
             ORDER BY procurementQuantity DESC, shippedQuantity DESC, remainingQuantity DESC, categoryName
             LIMIT 50
            </script>
            """)
    List<Map<String, Object>> inventoryItemSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("productCategoryId") Long productCategoryId);

    @Select("""
            <script>
            WITH sales_rows AS (
                SELECT l.product_id AS productId,
                       COALESCE(l.unit_code, 'UNKNOWN') AS unitCode,
                       COALESCE(MAX(l.product_code), CAST(l.product_id AS CHAR), 'UNKNOWN') AS productCode,
                       COALESCE(MAX(l.product_name), MAX(l.product_code), '未知商品') AS productName,
                       COALESCE(MAX(l.product_category_id), 0) AS categoryId,
                       COALESCE(MAX(l.product_category_code), CAST(MAX(l.product_category_id) AS CHAR), 'UNKNOWN') AS categoryCode,
                       COALESCE(MAX(l.product_category_name), CAST(MAX(l.product_category_id) AS CHAR), '未分配分类') AS categoryName,
                       COALESCE(SUM(l.quantity), 0) AS salesQuantity
                  FROM bi_sales_order_line_fact l
                 WHERE l.tenant_id = #{tenantId}
                   AND l.deleted = 0
                   AND l.order_status_code &lt;&gt; 'CANCELLED'
                   AND l.order_date &gt;= #{from}
                   AND l.order_date &lt;= #{to}
                   AND l.product_id IS NOT NULL
            <if test="regionCode != null">
                   AND l.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                   AND l.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                   AND l.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                   AND l.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                   AND l.source_system_code = #{sourceSystemCode}
            </if>
                 GROUP BY l.product_id, COALESCE(l.unit_code, 'UNKNOWN')
            ),
            inventory_rows AS (
                SELECT b.product_id AS productId,
                       COALESCE(b.unit_code, 'UNKNOWN') AS unitCode,
                       COALESCE(MAX(b.product_code), CAST(b.product_id AS CHAR), 'UNKNOWN') AS productCode,
                       COALESCE(MAX(b.product_name), MAX(b.product_code), '未知商品') AS productName,
                       COALESCE(MAX(b.product_category_id), 0) AS categoryId,
                       COALESCE(MAX(p.product_category_code), CAST(MAX(b.product_category_id) AS CHAR), 'UNKNOWN') AS categoryCode,
                       COALESCE(MAX(p.product_category_name), CAST(MAX(b.product_category_id) AS CHAR), '未分配分类') AS categoryName,
                       COALESCE(SUM(b.available_quantity), 0) AS availableQuantity,
                       COALESCE(SUM(b.in_transit_quantity), 0) AS inTransitQuantity,
                       MAX(CASE WHEN b.deleted &lt;&gt; 0 THEN 1 ELSE 0 END) AS inactiveStock
                  FROM bi_inventory_balance_current b
                  LEFT JOIN bi_product_dim p
                    ON p.tenant_id = b.tenant_id
                   AND p.product_id = b.product_id
                 WHERE b.tenant_id = #{tenantId}
                   AND (
                        b.deleted = 0
                        OR b.available_quantity &lt;&gt; 0
                        OR b.locked_quantity &lt;&gt; 0
                        OR b.in_transit_quantity &lt;&gt; 0
                   )
                   AND b.product_id IS NOT NULL
            <if test="regionCode != null">
                   AND b.region_code = #{regionCode}
            </if>
            <if test="productCategoryId != null">
                   AND b.product_category_id = #{productCategoryId}
            </if>
                 GROUP BY b.product_id, COALESCE(b.unit_code, 'UNKNOWN')
            ),
            replenishment_keys AS (
                SELECT productId, unitCode FROM sales_rows
                UNION
                SELECT productId, unitCode FROM inventory_rows
            )
            SELECT COALESCE(s.categoryCode, i.categoryCode, 'UNKNOWN') AS categoryCode,
                   COALESCE(s.categoryName, i.categoryName, '未分配分类') AS categoryName,
                   COALESCE(s.productCode, i.productCode, CAST(k.productId AS CHAR), 'UNKNOWN') AS productCode,
                   COALESCE(s.productName, i.productName, s.productCode, i.productCode, '未知商品') AS productName,
                   k.unitCode AS unitCode,
                   COALESCE(s.salesQuantity, 0) AS salesQuantity,
                   CASE WHEN COALESCE(s.salesQuantity, 0) = 0 THEN 0
                        ELSE COALESCE(s.salesQuantity, 0) / GREATEST(DATEDIFF(#{to}, #{from}) + 1, 1) END
                        AS dailySalesQuantity,
                   COALESCE(i.availableQuantity, 0) AS availableQuantity,
                   COALESCE(i.inTransitQuantity, 0) AS inTransitQuantity,
                   CASE WHEN COALESCE(s.salesQuantity, 0) = 0 THEN 0
                        ELSE COALESCE(i.availableQuantity, 0)
                            / (COALESCE(s.salesQuantity, 0) / GREATEST(DATEDIFF(#{to}, #{from}) + 1, 1)) END
                        AS coverageDays,
                   CASE WHEN COALESCE(s.salesQuantity, 0) = 0 THEN 0
                        ELSE GREATEST(
                            30 * (COALESCE(s.salesQuantity, 0) / GREATEST(DATEDIFF(#{to}, #{from}) + 1, 1))
                            - COALESCE(i.availableQuantity, 0)
                            - COALESCE(i.inTransitQuantity, 0),
                            0
                        ) END AS suggestedProcurementQuantity,
                   CASE
                       WHEN COALESCE(s.salesQuantity, 0) &gt; 0 AND COALESCE(i.availableQuantity, 0) &lt;= 0 THEN 'HIGH'
                       WHEN COALESCE(s.salesQuantity, 0) &gt; 0
                            AND COALESCE(i.availableQuantity, 0)
                                / (COALESCE(s.salesQuantity, 0) / GREATEST(DATEDIFF(#{to}, #{from}) + 1, 1)) &lt;= 7 THEN 'HIGH'
                       WHEN COALESCE(s.salesQuantity, 0) &gt; 0
                            AND COALESCE(i.availableQuantity, 0)
                                / (COALESCE(s.salesQuantity, 0) / GREATEST(DATEDIFF(#{to}, #{from}) + 1, 1)) &lt;= 15 THEN 'MEDIUM'
                       WHEN COALESCE(s.salesQuantity, 0) = 0 AND COALESCE(i.availableQuantity, 0) &gt; 0 THEN 'SLOW'
                       ELSE 'NORMAL'
                   END AS riskLevel,
                   CASE WHEN COALESCE(i.inactiveStock, 0) = 1 THEN 'HISTORICAL_STOCK'
                        ELSE 'NORMAL' END AS inventoryStatus
              FROM replenishment_keys k
              LEFT JOIN sales_rows s
                ON s.productId = k.productId
               AND s.unitCode = k.unitCode
              LEFT JOIN inventory_rows i
                ON i.productId = k.productId
               AND i.unitCode = k.unitCode
             ORDER BY CASE
                    WHEN COALESCE(s.salesQuantity, 0) &gt; 0 AND COALESCE(i.availableQuantity, 0) &lt;= 0 THEN 0
                    WHEN COALESCE(s.salesQuantity, 0) &gt; 0
                         AND COALESCE(i.availableQuantity, 0)
                             / (COALESCE(s.salesQuantity, 0) / GREATEST(DATEDIFF(#{to}, #{from}) + 1, 1)) &lt;= 7 THEN 0
                    WHEN COALESCE(s.salesQuantity, 0) &gt; 0
                         AND COALESCE(i.availableQuantity, 0)
                             / (COALESCE(s.salesQuantity, 0) / GREATEST(DATEDIFF(#{to}, #{from}) + 1, 1)) &lt;= 15 THEN 1
                    WHEN COALESCE(s.salesQuantity, 0) = 0 AND COALESCE(i.availableQuantity, 0) &gt; 0 THEN 3
                    ELSE 2
                END,
                suggestedProcurementQuantity DESC,
                salesQuantity DESC,
                productName
             LIMIT 50
            </script>
            """)
    List<Map<String, Object>> inventoryReplenishment(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT cc.regionCode AS regionCode,
                   cc.regionName AS regionName,
                   cc.costAmount AS costAmount,
                   cc.budgetAmount AS budgetAmount,
                   cc.varianceAmount AS varianceAmount,
                   COALESCE(s.salesAmount, 0) AS salesAmount,
                   CASE WHEN COALESCE(s.salesAmount, 0) = 0 THEN 0
                        ELSE cc.costAmount / s.salesAmount * 100 END AS costRate,
                   cc.recordCount AS recordCount,
                   cc.latestCostTime AS latestCostTime
              FROM (
                    SELECT region_code AS regionCode,
                           COALESCE(MAX(region_name), region_code) AS regionName,
                           COALESCE(SUM(cost_amount), 0) AS costAmount,
                           COALESCE(SUM(budget_amount), 0) AS budgetAmount,
                           COALESCE(SUM(cost_amount), 0) - COALESCE(SUM(budget_amount), 0) AS varianceAmount,
                           COUNT(*) AS recordCount,
                           MAX(cost_date) AS latestCostTime
                      FROM bi_city_cost_record
                     WHERE tenant_id = #{tenantId}
                       AND deleted = 0
                       AND cost_date &gt;= #{from}
                       AND cost_date &lt;= #{to}
            <if test="regionCode != null">
                       AND region_code = #{regionCode}
            </if>
                     GROUP BY region_code
              ) cc
              LEFT JOIN (
                    SELECT COALESCE(region_code, 'UNKNOWN') AS regionCode,
                           COALESCE(SUM(payable_amount), 0) AS salesAmount
                      FROM bi_sales_order_fact
                     WHERE tenant_id = #{tenantId}
                       AND deleted = 0
                       AND order_status_code &lt;&gt; 'CANCELLED'
                       AND order_date &gt;= #{from}
                       AND order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND source_system_code = #{sourceSystemCode}
            </if>
                     GROUP BY COALESCE(region_code, 'UNKNOWN')
              ) s ON s.regionCode = cc.regionCode
             ORDER BY cc.costAmount DESC, cc.latestCostTime DESC
             LIMIT 50
            </script>
            """)
    List<Map<String, Object>> cityCostRanking(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'INVENTORY' AS riskType,
                   CASE WHEN b.deleted &lt;&gt; 0
                             AND (b.available_quantity &lt;&gt; 0 OR b.locked_quantity &lt;&gt; 0 OR b.in_transit_quantity &lt;&gt; 0)
                        THEN 'MEDIUM'
                        WHEN b.available_quantity &lt;= 0 THEN 'HIGH'
                        WHEN b.available_quantity &lt; b.locked_quantity THEN 'MEDIUM'
                        ELSE 'LOW' END AS riskLevel,
                   CONCAT(COALESCE(b.warehouse_code, 'UNKNOWN'), '/', COALESCE(b.product_code, 'UNKNOWN'), '/', COALESCE(b.variant_code, 'UNKNOWN')) AS dimensionCode,
                   CONCAT(CASE WHEN b.deleted &lt;&gt; 0 THEN '历史/下架 - ' ELSE '' END,
                          COALESCE(b.warehouse_name, b.warehouse_code, '未知仓库'), ' - ',
                          COALESCE(b.product_name, b.product_code, '未知商品'), ' ',
                          COALESCE(b.specification_snapshot, b.variant_code, '')) AS dimensionName,
                   CASE WHEN b.deleted &lt;&gt; 0
                             AND (b.available_quantity &lt;&gt; 0 OR b.locked_quantity &lt;&gt; 0 OR b.in_transit_quantity &lt;&gt; 0)
                        THEN '历史/下架商品仍有库存'
                        WHEN b.available_quantity &lt;= 0 THEN '可用库存小于等于0'
                        ELSE '锁定库存高于可用库存' END AS description,
                   b.available_quantity AS primaryValue,
                   b.locked_quantity AS secondaryValue,
                   b.source_updated_time AS observedAt
             FROM bi_inventory_balance_current b
             WHERE b.tenant_id = #{tenantId}
               AND (
                    (b.deleted = 0 AND (b.available_quantity &lt;= 0 OR b.available_quantity &lt; b.locked_quantity))
                    OR (b.deleted &lt;&gt; 0
                        AND (b.available_quantity &lt;&gt; 0 OR b.locked_quantity &lt;&gt; 0 OR b.in_transit_quantity &lt;&gt; 0))
               )
            <if test="regionCode != null">
               AND b.region_code = #{regionCode}
            </if>
            <if test="productCategoryId != null">
               AND b.product_category_id = #{productCategoryId}
            </if>
             ORDER BY b.available_quantity ASC, b.source_updated_time DESC
             LIMIT 20
            </script>
            """)
    List<Map<String, Object>> inventoryRisks(
            @Param("tenantId") String tenantId,
            @Param("regionCode") String regionCode,
            @Param("productCategoryId") Long productCategoryId);

    @Select("""
            SELECT 'ORDER_SALES_ORDER' AS sourceCode, '销售订单' AS sourceName,
                   MAX(source_updated_time) AS latestUpdatedTime
              FROM bi_sales_order_fact
             WHERE tenant_id = #{tenantId} AND deleted = 0
            UNION ALL
            SELECT 'ORDER_PAYMENT_RECORD' AS sourceCode, '销售回款记录' AS sourceName,
                   MAX(source_updated_time) AS latestUpdatedTime
              FROM bi_sales_payment_fact
             WHERE tenant_id = #{tenantId} AND deleted = 0
            UNION ALL
            SELECT 'ORDER_SALES_ORDER_LINE' AS sourceCode, '销售订单行' AS sourceName,
                   MAX(source_updated_time) AS latestUpdatedTime
              FROM bi_sales_order_line_fact
             WHERE tenant_id = #{tenantId} AND deleted = 0
            UNION ALL
            SELECT 'CRM_CUSTOMER' AS sourceCode, '客户/门店' AS sourceName,
                   MAX(source_updated_time) AS latestUpdatedTime
              FROM bi_customer_dim
             WHERE tenant_id = #{tenantId} AND deleted = 0
            UNION ALL
            SELECT 'ERP_PRODUCT' AS sourceCode, 'ERP商品' AS sourceName,
                   MAX(source_updated_time) AS latestUpdatedTime
              FROM bi_product_dim
             WHERE tenant_id = #{tenantId} AND deleted = 0
            UNION ALL
            SELECT 'ERP_STOCK_BALANCE' AS sourceCode, '库存余额' AS sourceName,
                   MAX(source_updated_time) AS latestUpdatedTime
              FROM bi_inventory_balance_current
             WHERE tenant_id = #{tenantId} AND deleted = 0
            UNION ALL
            SELECT 'ERP_INVENTORY_OPERATION' AS sourceCode, '采购/发货流转' AS sourceName,
                   MAX(source_updated_time) AS latestUpdatedTime
              FROM bi_inventory_operation_fact
             WHERE tenant_id = #{tenantId} AND deleted = 0
            UNION ALL
            SELECT 'BI_CITY_COST_RECORD' AS sourceCode, '城市端成本' AS sourceName,
                   MAX(updated_time) AS latestUpdatedTime
              FROM bi_city_cost_record
             WHERE tenant_id = #{tenantId} AND deleted = 0
            UNION ALL
            SELECT 'BI_RECONCILIATION_CURRENT' AS sourceCode, '对账快照' AS sourceName,
                   MAX(observed_time) AS latestUpdatedTime
              FROM bi_supply_reconciliation_current
             WHERE tenant_id = #{tenantId} AND deleted = 0
            """)
    List<Map<String, Object>> dataFreshness(@Param("tenantId") String tenantId);

    @Select("""
            SELECT c.source_code AS sourceCode,
                   c.source_name AS sourceName,
                   c.last_watermark_time AS checkpointWatermarkTime,
                   c.last_success_time AS lastSuccessTime,
                   c.status_code AS checkpointStatus,
                   c.last_run_id AS lastRunId,
                   r.status_code AS lastRunStatus,
                   r.started_time AS lastRunStartedTime,
                   r.completed_time AS lastRunCompletedTime,
                   r.pulled_count AS pulledCount,
                   r.upserted_count AS upsertedCount,
                   r.skipped_count AS skippedCount,
                   COALESCE(c.failure_reason, r.failure_reason) AS failureReason
              FROM bi_etl_checkpoint c
              LEFT JOIN bi_etl_run r ON r.id = c.last_run_id
             WHERE c.tenant_id = #{tenantId}
               AND c.source_code <> 'SUPPLY_DASHBOARD_REFRESH_LOCK'
             ORDER BY c.source_code
            """)
    List<Map<String, Object>> trustSources(@Param("tenantId") String tenantId);

    @Select("""
            SELECT id, tenant_id AS tenantId, job_code AS jobCode, status_code AS statusCode,
                   started_time AS startedTime, completed_time AS completedTime,
                   watermark_time AS watermarkTime, pulled_count AS pulledCount,
                   upserted_count AS upsertedCount, skipped_count AS skippedCount,
                   failure_reason AS failureReason
              FROM bi_etl_run
             WHERE tenant_id = #{tenantId}
               AND job_code IN ('SUPPLY_DASHBOARD_MANUAL', 'SUPPLY_DASHBOARD_HOURLY')
             ORDER BY started_time DESC, id DESC
             LIMIT 1
            """)
    Map<String, Object> latestRefreshRun(@Param("tenantId") String tenantId);

    @Select("""
            SELECT optionType, optionValue, COALESCE(MAX(NULLIF(optionLabel, '')), optionValue) AS optionLabel,
                   SUM(usageCount) AS usageCount
              FROM (
                    SELECT 'REGION' AS optionType, region_code AS optionValue,
                           COALESCE(MAX(NULLIF(region_name, '')), region_code) AS optionLabel,
                           COUNT(*) AS usageCount
                      FROM bi_sales_order_fact
                     WHERE tenant_id = #{tenantId} AND deleted = 0 AND region_code IS NOT NULL
                     GROUP BY region_code
                    UNION ALL
                    SELECT 'REGION', region_code, COALESCE(MAX(region_name), region_code), COUNT(*)
                      FROM bi_city_cost_record
                     WHERE tenant_id = #{tenantId} AND deleted = 0 AND region_code IS NOT NULL
                     GROUP BY region_code
                    UNION ALL
                    SELECT 'REGION', region_code, COALESCE(MAX(NULLIF(region_name, '')), region_code), COUNT(*)
                      FROM bi_customer_dim
                     WHERE tenant_id = #{tenantId} AND deleted = 0 AND region_code IS NOT NULL
                     GROUP BY region_code
              ) options
             GROUP BY optionType, optionValue
             ORDER BY usageCount DESC, optionValue
             LIMIT 100
            """)
    List<Map<String, Object>> regionOptions(@Param("tenantId") String tenantId);

    @Select("""
            SELECT 'SALES_OWNER' AS optionType,
                   owner_staff_code AS optionValue,
                   COALESCE(MAX(owner_staff_name), owner_staff_code) AS optionLabel,
                   COUNT(*) AS usageCount
              FROM bi_sales_order_fact
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
               AND owner_staff_code IS NOT NULL
             GROUP BY owner_staff_code
             ORDER BY usageCount DESC, optionValue
             LIMIT 500
            """)
    List<Map<String, Object>> salesOwnerOptions(@Param("tenantId") String tenantId);

    @Select("""
            SELECT 'CUSTOMER_TYPE' AS optionType,
                   customer_type_code AS optionValue,
                   COALESCE(MAX(NULLIF(customer_type_name, '')), customer_type_code) AS optionLabel,
                   COUNT(*) AS usageCount
              FROM bi_customer_dim
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
               AND customer_type_code IS NOT NULL
             GROUP BY customer_type_code
             ORDER BY usageCount DESC, optionValue
             LIMIT 100
            """)
    List<Map<String, Object>> customerTypeOptions(@Param("tenantId") String tenantId);

    @Select("""
            SELECT 'PRODUCT_CATEGORY' AS optionType,
                   CAST(pc.id AS CHAR) AS optionValue,
                   pc.category_name AS optionLabel,
                   COALESCE(usage_rows.usageCount, 0) AS usageCount,
                   CAST(pc.parent_id AS CHAR) AS parentOptionValue,
                   pc.category_level AS categoryLevel,
                   pc.ordinal AS ordinal
              FROM rigour_erp.erp_product_category pc
              LEFT JOIN (
                    SELECT product_category_id, SUM(usageCount) AS usageCount
                      FROM (
                            SELECT product_category_id, COUNT(*) AS usageCount
                              FROM bi_sales_order_line_fact
                             WHERE tenant_id = #{tenantId}
                               AND deleted = 0
                               AND product_category_id IS NOT NULL
                             GROUP BY product_category_id
                            UNION ALL
                            SELECT product_category_id, COUNT(*) AS usageCount
                              FROM bi_inventory_balance_current
                             WHERE tenant_id = #{tenantId}
                               AND deleted = 0
                               AND product_category_id IS NOT NULL
                             GROUP BY product_category_id
                      ) usage_options
                     GROUP BY product_category_id
              ) usage_rows ON usage_rows.product_category_id = pc.id
             WHERE pc.tenant_id = #{tenantId}
               AND pc.deleted = 0
             ORDER BY pc.category_level, pc.ordinal, pc.category_code, pc.id
             LIMIT 500
            """)
    List<Map<String, Object>> productCategoryOptions(@Param("tenantId") String tenantId);

    @Select("""
            SELECT 'SOURCE_SYSTEM' AS optionType,
                   source_system_code AS optionValue,
                   CASE source_system_code
                       WHEN 'DINGHUOBAO' THEN '订货宝'
                       WHEN 'MANUAL' THEN '手工订单'
                       ELSE source_system_code
                   END AS optionLabel,
                   COUNT(*) AS usageCount
              FROM bi_sales_order_fact
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
               AND source_system_code IS NOT NULL
             GROUP BY source_system_code
             ORDER BY usageCount DESC, optionValue
             LIMIT 100
            """)
    List<Map<String, Object>> sourceSystemOptions(@Param("tenantId") String tenantId);

    @Select("""
            SELECT subject_code AS subjectCode,
                   subject_name AS subjectName,
                   from_time AS fromTime,
                   to_time AS toTime,
                   source_row_count AS sourceRowCount,
                   business_row_count AS businessRowCount,
                   bi_row_count AS biRowCount,
                   source_amount AS sourceAmount,
                   business_amount AS businessAmount,
                   bi_amount AS biAmount,
                   observed_time AS observedAt
              FROM bi_supply_reconciliation_current
             WHERE tenant_id = #{tenantId}
               AND scope_code = 'TENANT_CURRENT_MONTH'
               AND deleted = 0
             ORDER BY CASE subject_code
                          WHEN 'SALES_ORDER' THEN 10
                          WHEN 'CITY_ATTRIBUTION' THEN 15
                          WHEN 'SALES_PAYMENT' THEN 20
                          WHEN 'CRM_CUSTOMER' THEN 30
                          WHEN 'CRM_CUSTOMER_CONTACT' THEN 35
                          WHEN 'ERP_PRODUCT' THEN 40
                          WHEN 'ERP_SALEABLE_PRODUCT' THEN 45
                          WHEN 'SALES_ORDER_LINE' THEN 50
                          WHEN 'ESTIMATED_GROSS_PROFIT' THEN 55
                          WHEN 'PAYMENT_RISK' THEN 60
                          WHEN 'ERP_STOCK_BALANCE' THEN 70
                          WHEN 'ERP_INVENTORY_OPERATION' THEN 75
                          WHEN 'BI_BUSINESS_TARGET' THEN 80
                          WHEN 'CITY_COST_IMPORT' THEN 90
                          ELSE 100
                      END,
                      subject_code
            """)
    List<Map<String, Object>> currentReconciliation(@Param("tenantId") String tenantId);

    @Select("""
            <script>
            WITH feishu_sales_order_source AS (
                SELECT ranked.source_document_no,
                       ranked.source_date,
                       ranked.source_amount
                  FROM (
                        SELECT raw.source_document_no,
                               COALESCE(
                                   STR_TO_DATE(raw.date_value, '%Y-%m-%d %H:%i:%s'),
                                   STR_TO_DATE(raw.date_value, '%Y/%m/%d %H:%i:%s'),
                                   STR_TO_DATE(raw.date_value, '%Y-%m-%d'),
                                   STR_TO_DATE(raw.date_value, '%Y/%m/%d'),
                                   raw.source_created_at
                               ) AS source_date,
                               CAST(NULLIF(TRIM(REPLACE(REPLACE(REPLACE(REPLACE(
                                   COALESCE(raw.amount_value, '0'), ',', ''), '¥', ''), '￥', ''), '元', '')), '')
                                   AS DECIMAL(24,6)) AS source_amount,
                               ROW_NUMBER() OVER (
                                   PARTITION BY raw.source_document_no
                                   ORDER BY raw.updated_at DESC, raw.created_at DESC
                               ) AS row_rank
                          FROM (
                                SELECT r.source_document_no,
                                       r.source_created_at,
                                       r.updated_at,
                                       r.created_at,
                                       COALESCE(
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."销售日期"')), ''),
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."下单时间"')), ''),
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."订单时间"')), ''),
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."创建时间"')), '')
                                       ) AS date_value,
                                       COALESCE(
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."实际小计"')), ''),
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."收款合计"')), ''),
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."小计"')), ''),
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."已回款额"')), ''),
                                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."已付金额"')), ''),
                                           '0'
                                       ) AS amount_value
                                  FROM rigour_integration.integration_feishu_import_raw_row r
                                 WHERE r.tenant_id = UUID_TO_BIN(#{tenantId})
                                   AND r.table_code = 'FEISHU_SALES_ORDER'
                                   AND r.import_status = 'IMPORTED'
                                   AND r.source_document_no IS NOT NULL
                                   AND r.source_document_no &lt;&gt; ''
                          ) raw
                  ) ranked
                 WHERE ranked.row_rank = 1
                   AND ranked.source_date &gt;= #{from}
                   AND ranked.source_date &lt;= #{to}
            )
            SELECT 'SALES_ORDER' AS subjectCode,
                   '销售订单' AS subjectName,
                   (
                       CASE WHEN #{sourceSystemCode} IS NULL OR #{sourceSystemCode} = 'DINGHUOBAO' THEN (
                        SELECT COUNT(DISTINCT r.source_id)
                          FROM rigour_integration.integration_raw_landing r
                         WHERE r.tenant_id = UUID_TO_BIN(#{tenantId})
                           AND r.source_system = 'DHB'
                           AND r.source_object_type = 'SALES_ORDER'
                           AND r.received_at &gt;= #{from}
                           AND r.received_at &lt;= #{to}
                       ) ELSE 0 END
                       + CASE WHEN #{sourceSystemCode} IS NULL OR #{sourceSystemCode} = 'FEISHU' THEN (
                           SELECT COUNT(*)
                             FROM feishu_sales_order_source
                       ) ELSE 0 END
                   ) AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_order.order_sales_order o
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND o.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_sales_order_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.order_status_code &lt;&gt; 'CANCELLED'
                       AND b.order_date &gt;= #{from}
                       AND b.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biRowCount,
                   CASE WHEN #{sourceSystemCode} = 'FEISHU' THEN (
                       SELECT COALESCE(SUM(source_amount), 0)
                         FROM feishu_sales_order_source
                   ) ELSE 0 END AS sourceAmount,
                   (SELECT COALESCE(SUM(o.payable_amount), 0)
                      FROM rigour_order.order_sales_order o
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND o.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS businessAmount,
                   (SELECT COALESCE(SUM(b.payable_amount), 0)
                      FROM bi_sales_order_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.order_status_code &lt;&gt; 'CANCELLED'
                       AND b.order_date &gt;= #{from}
                       AND b.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biAmount
            </script>
            """)
    Map<String, Object> salesOrderReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'CITY_ATTRIBUTION' AS subjectCode,
                   '城市归属' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_order.order_sales_order o
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_order.order_sales_order o
                      INNER JOIN bi_sales_order_fact b
                         ON b.tenant_id = o.tenant_id
                        AND b.order_id = o.id
                        AND b.deleted = 0
                        AND b.order_status_code &lt;&gt; 'CANCELLED'
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''), '') = COALESCE(b.region_code, '')) AS biRowCount,
                   0 AS sourceAmount,
                   (SELECT COALESCE(SUM(o.payable_amount), 0)
                      FROM rigour_order.order_sales_order o
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                   ) AS businessAmount,
                   (SELECT COALESCE(SUM(o.payable_amount), 0)
                      FROM rigour_order.order_sales_order o
                      INNER JOIN bi_sales_order_fact b
                         ON b.tenant_id = o.tenant_id
                        AND b.order_id = o.id
                        AND b.deleted = 0
                        AND b.order_status_code &lt;&gt; 'CANCELLED'
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''), '') = COALESCE(b.region_code, '')) AS biAmount
            </script>
            """)
    Map<String, Object> cityAttributionReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            WITH feishu_payment_raw AS (
                SELECT r.source_document_no,
                       r.source_created_at,
                       r.updated_at,
                       r.created_at,
                       COALESCE(
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."回款日期"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."回款日期门店"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."收款日期"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."付款日期"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."创建时间"')), '')
                       ) AS date_value,
                       COALESCE(
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."实际回款额"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."回款金额"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."收款合计"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."已回款额"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."已付金额"')), ''),
                           '0'
                       ) AS amount_value
                  FROM rigour_integration.integration_feishu_import_raw_row r
                 WHERE r.tenant_id = UUID_TO_BIN(#{tenantId})
                   AND r.table_code = 'FEISHU_SALES_ORDER'
                   AND r.import_status = 'IMPORTED'
                   AND r.source_document_no IS NOT NULL
                   AND r.source_document_no &lt;&gt; ''
                UNION ALL
                SELECT r.source_document_no,
                       r.source_created_at,
                       r.updated_at,
                       r.created_at,
                       COALESCE(
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."回款日期"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."回款日期门店"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."收款日期"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."付款日期"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."创建时间"')), '')
                       ) AS date_value,
                       COALESCE(
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."实际回款额"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."回款金额"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."收款合计"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."已回款额"')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.row_json, '$."已付金额"')), ''),
                           '0'
                       ) AS amount_value
                  FROM rigour_integration.integration_feishu_import_raw_row r
                 WHERE r.tenant_id = UUID_TO_BIN(#{tenantId})
                   AND r.table_code = 'FEISHU_PAYMENT_RECORD'
                   AND r.import_status = 'IMPORTED'
                   AND r.source_document_no IS NOT NULL
                   AND r.source_document_no &lt;&gt; ''
            ),
            feishu_payment_source AS (
                SELECT ranked.source_document_no,
                       ranked.source_date,
                       ranked.source_amount
                  FROM (
                        SELECT raw.source_document_no,
                               COALESCE(
                                   STR_TO_DATE(raw.date_value, '%Y-%m-%d %H:%i:%s'),
                                   STR_TO_DATE(raw.date_value, '%Y/%m/%d %H:%i:%s'),
                                   STR_TO_DATE(raw.date_value, '%Y-%m-%d'),
                                   STR_TO_DATE(raw.date_value, '%Y/%m/%d'),
                                   raw.source_created_at
                               ) AS source_date,
                               CAST(NULLIF(TRIM(REPLACE(REPLACE(REPLACE(REPLACE(
                                   COALESCE(raw.amount_value, '0'), ',', ''), '¥', ''), '￥', ''), '元', '')), '')
                                   AS DECIMAL(24,6)) AS source_amount,
                               ROW_NUMBER() OVER (
                                   PARTITION BY raw.source_document_no
                                   ORDER BY raw.updated_at DESC, raw.created_at DESC
                               ) AS row_rank
                          FROM feishu_payment_raw raw
                  ) ranked
                 WHERE ranked.row_rank = 1
                   AND ranked.source_amount &gt; 0
                   AND ranked.source_date &gt;= #{from}
                   AND ranked.source_date &lt;= #{to}
            )
            SELECT 'SALES_PAYMENT' AS subjectCode,
                   '销售回款' AS subjectName,
                   CASE WHEN #{sourceSystemCode} IS NULL OR #{sourceSystemCode} = 'FEISHU' THEN (
                       SELECT COUNT(*)
                         FROM feishu_payment_source
                   ) ELSE 0 END AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_order.order_payment_record p
                      LEFT JOIN rigour_order.order_sales_order o
                        ON o.tenant_id = p.tenant_id AND o.id = p.order_id
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = p.tenant_id AND c.id = COALESCE(p.customer_id, o.customer_id)
                     WHERE p.tenant_id = #{tenantId}
                       AND p.deleted = 0
                       AND p.payment_time &gt;= #{from}
                       AND p.payment_time &lt;= #{to}
            <if test="regionCode != null">
                       AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND (COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
                            OR NULLIF(p.collector_staff_code, '') = #{ownerStaffCode})
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND o.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_sales_payment_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.payment_time &gt;= #{from}
                       AND b.payment_time &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND (b.owner_staff_code = #{ownerStaffCode} OR b.collector_staff_code = #{ownerStaffCode})
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biRowCount,
                   CASE WHEN #{sourceSystemCode} = 'FEISHU' THEN (
                       SELECT COALESCE(SUM(source_amount), 0)
                         FROM feishu_payment_source
                   ) ELSE 0 END AS sourceAmount,
                   (SELECT COALESCE(SUM(p.paid_amount), 0)
                      FROM rigour_order.order_payment_record p
                      LEFT JOIN rigour_order.order_sales_order o
                        ON o.tenant_id = p.tenant_id AND o.id = p.order_id
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = p.tenant_id AND c.id = COALESCE(p.customer_id, o.customer_id)
                     WHERE p.tenant_id = #{tenantId}
                       AND p.deleted = 0
                       AND p.payment_time &gt;= #{from}
                       AND p.payment_time &lt;= #{to}
            <if test="regionCode != null">
                       AND o.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND (COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
                            OR NULLIF(p.collector_staff_code, '') = #{ownerStaffCode})
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND o.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS businessAmount,
                   (SELECT COALESCE(SUM(b.paid_amount), 0)
                      FROM bi_sales_payment_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.payment_time &gt;= #{from}
                       AND b.payment_time &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND (b.owner_staff_code = #{ownerStaffCode} OR b.collector_staff_code = #{ownerStaffCode})
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biAmount
            </script>
            """)
    Map<String, Object> paymentReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'CRM_CUSTOMER' AS subjectCode,
                   '客户/门店' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_crm.crm_customer c
                     WHERE c.tenant_id = #{tenantId}
                       AND c.deleted = 0
                       AND c.status_code = 'ACTIVE'
            <if test="regionCode != null">
                       AND c.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND c.owner_employee_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_customer_dim b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.status_code = 'ACTIVE'
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   0 AS businessAmount,
                   0 AS biAmount
            </script>
            """)
    Map<String, Object> customerReconciliation(
            @Param("tenantId") String tenantId,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode);

    @Select("""
            <script>
            SELECT 'CRM_CUSTOMER_CONTACT' AS subjectCode,
                   '建联客户' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_crm.crm_customer c
                      LEFT JOIN (
                            SELECT tenant_id, party_id
                              FROM rigour_crm.crm_contact
                             WHERE deleted = 0
                               AND status = 'ACTIVE'
                               AND (
                                    NULLIF(TRIM(COALESCE(contact_name, '')), '') IS NOT NULL
                                    OR NULLIF(TRIM(COALESCE(phone, '')), '') IS NOT NULL
                               )
                             GROUP BY tenant_id, party_id
                      ) contact ON contact.tenant_id = UUID_TO_BIN(c.tenant_id)
                                AND contact.party_id = c.party_id
                     WHERE c.tenant_id = #{tenantId}
                       AND c.deleted = 0
                       AND c.status_code = 'ACTIVE'
                       AND (
                            NULLIF(TRIM(COALESCE(c.contact_name, '')), '') IS NOT NULL
                            OR NULLIF(TRIM(COALESCE(c.contact_phone, '')), '') IS NOT NULL
                            OR contact.party_id IS NOT NULL
                       )
            <if test="regionCode != null">
                       AND c.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND c.owner_employee_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_customer_dim b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.status_code = 'ACTIVE'
                       AND b.has_contact = 1
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   0 AS businessAmount,
                   0 AS biAmount
            </script>
            """)
    Map<String, Object> customerContactReconciliation(
            @Param("tenantId") String tenantId,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode);

    @Select("""
            <script>
            SELECT 'ERP_PRODUCT' AS subjectCode,
                   'ERP商品' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_erp.erp_product p
                     WHERE p.tenant_id = #{tenantId}
                       AND p.deleted = 0
            <if test="productCategoryId != null">
                       AND p.category_id = #{productCategoryId}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_product_dim b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   0 AS businessAmount,
                   0 AS biAmount
            </script>
            """)
    Map<String, Object> productReconciliation(
            @Param("tenantId") String tenantId,
            @Param("productCategoryId") Long productCategoryId);

    @Select("""
            <script>
            SELECT 'ERP_SALEABLE_PRODUCT' AS subjectCode,
                   '可售商品' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_erp.erp_product p
                     WHERE p.tenant_id = #{tenantId}
                       AND p.deleted = 0
                       AND p.shelf_status_code = 'ON_SHELF'
                       AND p.submit_status_code = 'SUBMITTED'
            <if test="productCategoryId != null">
                       AND p.category_id = #{productCategoryId}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_product_dim b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.shelf_status_code = 'ON_SHELF'
                       AND b.submit_status_code = 'SUBMITTED'
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   0 AS businessAmount,
                   0 AS biAmount
            </script>
            """)
    Map<String, Object> saleableProductReconciliation(
            @Param("tenantId") String tenantId,
            @Param("productCategoryId") Long productCategoryId);

    @Select("""
            <script>
            SELECT 'SALES_ORDER_LINE' AS subjectCode,
                   '商品销售订单行' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_order.order_sales_order_line l
                      INNER JOIN rigour_order.order_sales_order o
                        ON o.tenant_id = l.tenant_id AND o.id = l.order_id
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                      LEFT JOIN rigour_erp.erp_product p
                        ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                     WHERE l.tenant_id = #{tenantId}
                       AND l.deleted = 0
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                       AND p.category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_sales_order_line_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.order_status_code &lt;&gt; 'CANCELLED'
                       AND b.order_date &gt;= #{from}
                       AND b.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   (SELECT COALESCE(SUM(l.line_amount), 0)
                      FROM rigour_order.order_sales_order_line l
                      INNER JOIN rigour_order.order_sales_order o
                        ON o.tenant_id = l.tenant_id AND o.id = l.order_id
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                      LEFT JOIN rigour_erp.erp_product p
                        ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                     WHERE l.tenant_id = #{tenantId}
                       AND l.deleted = 0
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                       AND p.category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                   ) AS businessAmount,
                   (SELECT COALESCE(SUM(b.line_amount), 0)
                      FROM bi_sales_order_line_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.order_status_code &lt;&gt; 'CANCELLED'
                       AND b.order_date &gt;= #{from}
                       AND b.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biAmount
            </script>
            """)
    Map<String, Object> orderLineReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'ESTIMATED_GROSS_PROFIT' AS subjectCode,
                   '估算毛利' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_order.order_sales_order_line l
                      INNER JOIN rigour_order.order_sales_order o
                        ON o.tenant_id = l.tenant_id AND o.id = l.order_id
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                      LEFT JOIN rigour_erp.erp_product p
                        ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                      LEFT JOIN rigour_erp.erp_product_variant v
                        ON v.tenant_id = l.tenant_id AND v.id = l.product_variant_id
                     WHERE l.tenant_id = #{tenantId}
                       AND l.deleted = 0
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                       AND p.category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_sales_order_line_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.order_status_code &lt;&gt; 'CANCELLED'
                       AND b.order_date &gt;= #{from}
                       AND b.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   (SELECT COALESCE(SUM(COALESCE(l.line_amount, 0)
                              - CASE WHEN COALESCE(ls.orderLineAmount, 0) = 0 THEN 0
                                     ELSE COALESCE(rs.refundAmount, 0) * COALESCE(l.line_amount, 0) / ls.orderLineAmount END
                              - COALESCE(l.quantity, 0) * COALESCE(v.purchase_price, 0)), 0)
                      FROM rigour_order.order_sales_order_line l
                      INNER JOIN rigour_order.order_sales_order o
                        ON o.tenant_id = l.tenant_id AND o.id = l.order_id
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                      LEFT JOIN rigour_erp.erp_product p
                        ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                      LEFT JOIN rigour_erp.erp_product_variant v
                        ON v.tenant_id = l.tenant_id AND v.id = l.product_variant_id
                      LEFT JOIN (
                            SELECT tenant_id, order_id, COALESCE(SUM(refund_amount), 0) AS refundAmount
                              FROM rigour_order.order_refund_record
                             WHERE deleted = 0
                               AND refund_status_code &lt;&gt; 'CANCELLED'
                             GROUP BY tenant_id, order_id
                      ) rs ON rs.tenant_id = o.tenant_id AND rs.order_id = o.id
                      LEFT JOIN (
                            SELECT tenant_id, order_id, COALESCE(SUM(line_amount), 0) AS orderLineAmount
                              FROM rigour_order.order_sales_order_line
                             WHERE deleted = 0
                             GROUP BY tenant_id, order_id
                      ) ls ON ls.tenant_id = l.tenant_id AND ls.order_id = l.order_id
                     WHERE l.tenant_id = #{tenantId}
                       AND l.deleted = 0
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                       AND p.category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                   ) AS businessAmount,
                   (SELECT COALESCE(SUM(b.estimated_gross_profit_amount), 0)
                      FROM bi_sales_order_line_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.order_status_code &lt;&gt; 'CANCELLED'
                       AND b.order_date &gt;= #{from}
                       AND b.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biAmount
            </script>
            """)
    Map<String, Object> estimatedGrossProfitReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("productCategoryId") Long productCategoryId,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'PAYMENT_RISK' AS subjectCode,
                   '回款风险' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_order.order_sales_order o
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                      LEFT JOIN rigour_crm.crm_customer_policy policy
                        ON policy.tenant_id = UUID_TO_BIN(o.tenant_id)
                       AND policy.party_id = c.party_id
                       AND policy.deleted = 0
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.unpaid_amount &gt; 0
                       AND DATE_ADD(o.order_date, INTERVAL COALESCE(policy.payment_term_days, 30) DAY) &lt; #{to}
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_sales_order_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.order_status_code &lt;&gt; 'CANCELLED'
                       AND b.unpaid_amount &gt; 0
                       AND b.payment_due_date &lt; #{to}
                       AND b.order_date &gt;= #{from}
                       AND b.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   (SELECT COALESCE(SUM(o.unpaid_amount), 0)
                      FROM rigour_order.order_sales_order o
                      LEFT JOIN rigour_crm.crm_customer c
                        ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                      LEFT JOIN rigour_crm.crm_customer_policy policy
                        ON policy.tenant_id = UUID_TO_BIN(o.tenant_id)
                       AND policy.party_id = c.party_id
                       AND policy.deleted = 0
                     WHERE o.tenant_id = #{tenantId}
                       AND o.deleted = 0
                       AND o.order_status_code &lt;&gt; 'CANCELLED'
                       AND o.unpaid_amount &gt; 0
                       AND DATE_ADD(o.order_date, INTERVAL COALESCE(policy.payment_term_days, 30) DAY) &lt; #{to}
                       AND o.order_date &gt;= #{from}
                       AND o.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')) = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, '')) = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND c.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND COALESCE(o.source_system_code, 'MANUAL') = #{sourceSystemCode}
            </if>
                   ) AS businessAmount,
                   (SELECT COALESCE(SUM(b.unpaid_amount), 0)
                      FROM bi_sales_order_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.order_status_code &lt;&gt; 'CANCELLED'
                       AND b.unpaid_amount &gt; 0
                       AND b.payment_due_date &lt; #{to}
                       AND b.order_date &gt;= #{from}
                       AND b.order_date &lt;= #{to}
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="ownerStaffCode != null">
                       AND b.owner_staff_code = #{ownerStaffCode}
            </if>
            <if test="customerTypeCode != null">
                       AND b.customer_type_code = #{customerTypeCode}
            </if>
            <if test="sourceSystemCode != null">
                       AND b.source_system_code = #{sourceSystemCode}
            </if>
                   ) AS biAmount
            </script>
            """)
    Map<String, Object> paymentRiskReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode,
            @Param("ownerStaffCode") String ownerStaffCode,
            @Param("customerTypeCode") String customerTypeCode,
            @Param("sourceSystemCode") String sourceSystemCode);

    @Select("""
            <script>
            SELECT 'ERP_STOCK_BALANCE' AS subjectCode,
                   '库存余额' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM rigour_erp.erp_stock_balance s
                      LEFT JOIN rigour_erp.erp_inventory_warehouse w
                        ON w.tenant_id = s.tenant_id AND w.id = s.warehouse_id
                      LEFT JOIN rigour_erp.erp_product p
                        ON p.tenant_id = s.tenant_id AND p.id = s.product_id
                      LEFT JOIN rigour_erp.erp_product_variant v
                        ON v.tenant_id = s.tenant_id AND v.id = s.product_variant_id
                     WHERE s.tenant_id = #{tenantId}
                       AND COALESCE(s.deleted, 0) = 0
                       AND w.id IS NOT NULL
                       AND p.id IS NOT NULL
                       AND v.id IS NOT NULL
                       AND (
                            (
                                COALESCE(w.deleted, 0) = 0
                                AND COALESCE(p.deleted, 0) = 0
                                AND COALESCE(v.deleted, 0) = 0
                            )
                            OR s.available_quantity &lt;&gt; 0
                            OR s.locked_quantity &lt;&gt; 0
                            OR s.in_transit_quantity &lt;&gt; 0
                       )
            <if test="regionCode != null">
                       AND w.region_code = #{regionCode}
            </if>
            <if test="productCategoryId != null">
                       AND p.category_id = #{productCategoryId}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_inventory_balance_current b
                     WHERE b.tenant_id = #{tenantId}
                       AND (
                            b.deleted = 0
                            OR b.available_quantity &lt;&gt; 0
                            OR b.locked_quantity &lt;&gt; 0
                            OR b.in_transit_quantity &lt;&gt; 0
                       )
            <if test="regionCode != null">
                       AND b.region_code = #{regionCode}
            </if>
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   0 AS businessAmount,
                   0 AS biAmount
            </script>
            """)
    Map<String, Object> inventoryReconciliation(
            @Param("tenantId") String tenantId,
            @Param("regionCode") String regionCode,
            @Param("productCategoryId") Long productCategoryId);

    @Select("""
            <script>
            SELECT 'ERP_INVENTORY_OPERATION' AS subjectCode,
                   '采购/发货流转数量' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COALESCE(SUM(rowCount), 0)
                      FROM (
                            SELECT COUNT(*) AS rowCount
                              FROM rigour_erp.erp_procurement_order_line l
                              INNER JOIN rigour_erp.erp_procurement_order po
                                ON po.tenant_id = l.tenant_id AND po.id = l.procurement_order_id
                              LEFT JOIN rigour_erp.erp_product p
                                ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                             WHERE l.tenant_id = #{tenantId}
                               AND l.deleted = 0
                               AND po.deleted = 0
                               AND po.status_code NOT IN ('DRAFT', 'CANCELLED')
                               AND COALESCE(po.expected_arrival_time, po.created_time) &gt;= #{from}
                               AND COALESCE(po.expected_arrival_time, po.created_time) &lt;= #{to}
            <if test="productCategoryId != null">
                               AND p.category_id = #{productCategoryId}
            </if>
                            UNION ALL
                            SELECT COUNT(*)
                              FROM rigour_erp.erp_stock_out_order_line l
                              INNER JOIN rigour_erp.erp_stock_out_order so
                                ON so.tenant_id = l.tenant_id AND so.id = l.stock_out_order_id
                              LEFT JOIN rigour_erp.erp_product p
                                ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                             WHERE l.tenant_id = #{tenantId}
                               AND l.deleted = 0
                               AND so.deleted = 0
                               AND so.stock_out_type_code = 'SALES'
                               AND so.status_code NOT IN ('DRAFT', 'CANCELLED')
                               AND COALESCE(so.stock_out_time, so.created_time) &gt;= #{from}
                               AND COALESCE(so.stock_out_time, so.created_time) &lt;= #{to}
            <if test="productCategoryId != null">
                               AND p.category_id = #{productCategoryId}
            </if>
                      ) inventory_operation_rows
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_inventory_operation_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.operation_time &gt;= #{from}
                       AND b.operation_time &lt;= #{to}
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   (SELECT COALESCE(SUM(quantity), 0)
                      FROM (
                            SELECT COALESCE(SUM(l.quantity), 0) AS quantity
                              FROM rigour_erp.erp_procurement_order_line l
                              INNER JOIN rigour_erp.erp_procurement_order po
                                ON po.tenant_id = l.tenant_id AND po.id = l.procurement_order_id
                              LEFT JOIN rigour_erp.erp_product p
                                ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                             WHERE l.tenant_id = #{tenantId}
                               AND l.deleted = 0
                               AND po.deleted = 0
                               AND po.status_code NOT IN ('DRAFT', 'CANCELLED')
                               AND COALESCE(po.expected_arrival_time, po.created_time) &gt;= #{from}
                               AND COALESCE(po.expected_arrival_time, po.created_time) &lt;= #{to}
            <if test="productCategoryId != null">
                               AND p.category_id = #{productCategoryId}
            </if>
                            UNION ALL
                            SELECT COALESCE(SUM(l.quantity), 0)
                              FROM rigour_erp.erp_stock_out_order_line l
                              INNER JOIN rigour_erp.erp_stock_out_order so
                                ON so.tenant_id = l.tenant_id AND so.id = l.stock_out_order_id
                              LEFT JOIN rigour_erp.erp_product p
                                ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                             WHERE l.tenant_id = #{tenantId}
                               AND l.deleted = 0
                               AND so.deleted = 0
                               AND so.stock_out_type_code = 'SALES'
                               AND so.status_code NOT IN ('DRAFT', 'CANCELLED')
                               AND COALESCE(so.stock_out_time, so.created_time) &gt;= #{from}
                               AND COALESCE(so.stock_out_time, so.created_time) &lt;= #{to}
            <if test="productCategoryId != null">
                               AND p.category_id = #{productCategoryId}
            </if>
                      ) inventory_operation_quantities
                   ) AS businessAmount,
                   (SELECT COALESCE(SUM(b.quantity), 0)
                      FROM bi_inventory_operation_fact b
                     WHERE b.tenant_id = #{tenantId}
                       AND b.deleted = 0
                       AND b.operation_time &gt;= #{from}
                       AND b.operation_time &lt;= #{to}
            <if test="productCategoryId != null">
                       AND b.product_category_id = #{productCategoryId}
            </if>
                   ) AS biAmount
            </script>
            """)
    Map<String, Object> inventoryOperationReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("productCategoryId") Long productCategoryId);

    @Select("""
            SELECT 'BI_BUSINESS_TARGET' AS subjectCode,
                   '经营目标配置' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM bi_business_target t
                     WHERE t.tenant_id = #{tenantId}
                       AND t.deleted = 0
                       AND t.target_month >= DATE_FORMAT(#{from}, '%Y-%m-01')
                       AND t.target_month <= DATE_FORMAT(#{to}, '%Y-%m-01')) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_business_target t
                     WHERE t.tenant_id = #{tenantId}
                       AND t.deleted = 0
                       AND t.target_month >= DATE_FORMAT(#{from}, '%Y-%m-01')
                       AND t.target_month <= DATE_FORMAT(#{to}, '%Y-%m-01')) AS biRowCount,
                   0 AS sourceAmount,
                   (SELECT COALESCE(SUM(t.target_value), 0)
                      FROM bi_business_target t
                     WHERE t.tenant_id = #{tenantId}
                       AND t.deleted = 0
                       AND t.target_month >= DATE_FORMAT(#{from}, '%Y-%m-01')
                       AND t.target_month <= DATE_FORMAT(#{to}, '%Y-%m-01')) AS businessAmount,
                   (SELECT COALESCE(SUM(t.target_value), 0)
                      FROM bi_business_target t
                     WHERE t.tenant_id = #{tenantId}
                       AND t.deleted = 0
                       AND t.target_month >= DATE_FORMAT(#{from}, '%Y-%m-01')
                       AND t.target_month <= DATE_FORMAT(#{to}, '%Y-%m-01')) AS biAmount
            """)
    Map<String, Object> businessTargetReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Select("""
            <script>
            SELECT 'CITY_COST_IMPORT' AS subjectCode,
                   '城市端成本导入事实' AS subjectName,
                   0 AS sourceRowCount,
                   (SELECT COUNT(*)
                      FROM bi_city_cost_record c
                     WHERE c.tenant_id = #{tenantId}
                       AND c.deleted = 0
                       AND c.cost_date &gt;= #{from}
                       AND c.cost_date &lt;= #{to}
            <if test="regionCode != null">
                       AND c.region_code = #{regionCode}
            </if>
                   ) AS businessRowCount,
                   (SELECT COUNT(*)
                      FROM bi_city_cost_record c
                     WHERE c.tenant_id = #{tenantId}
                       AND c.deleted = 0
                       AND c.cost_date &gt;= #{from}
                       AND c.cost_date &lt;= #{to}
            <if test="regionCode != null">
                       AND c.region_code = #{regionCode}
            </if>
                   ) AS biRowCount,
                   0 AS sourceAmount,
                   (SELECT COALESCE(SUM(c.cost_amount), 0)
                      FROM bi_city_cost_record c
                     WHERE c.tenant_id = #{tenantId}
                       AND c.deleted = 0
                       AND c.cost_date &gt;= #{from}
                       AND c.cost_date &lt;= #{to}
            <if test="regionCode != null">
                       AND c.region_code = #{regionCode}
            </if>
                   ) AS businessAmount,
                   (SELECT COALESCE(SUM(c.cost_amount), 0)
                      FROM bi_city_cost_record c
                     WHERE c.tenant_id = #{tenantId}
                       AND c.deleted = 0
                       AND c.cost_date &gt;= #{from}
                       AND c.cost_date &lt;= #{to}
            <if test="regionCode != null">
                       AND c.region_code = #{regionCode}
            </if>
                   ) AS biAmount
            </script>
            """)
    Map<String, Object> cityCostReconciliation(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("regionCode") String regionCode);

    @Insert("""
            INSERT INTO bi_supply_reconciliation_current (
                tenant_id, subject_code, subject_name, scope_code, scope_name,
                from_time, to_time, source_row_count, business_row_count, bi_row_count,
                source_amount, business_amount, bi_amount, observed_time,
                created_time, updated_time, deleted
            ) VALUES (
                #{tenantId}, #{subjectCode}, #{subjectName}, #{scopeCode}, #{scopeName},
                #{from}, #{to}, #{sourceRowCount}, #{businessRowCount}, #{biRowCount},
                #{sourceAmount}, #{businessAmount}, #{biAmount}, #{observedAt},
                #{observedAt}, #{observedAt}, 0
            )
            ON DUPLICATE KEY UPDATE
                subject_name = VALUES(subject_name),
                scope_name = VALUES(scope_name),
                from_time = VALUES(from_time),
                to_time = VALUES(to_time),
                source_row_count = VALUES(source_row_count),
                business_row_count = VALUES(business_row_count),
                bi_row_count = VALUES(bi_row_count),
                source_amount = VALUES(source_amount),
                business_amount = VALUES(business_amount),
                bi_amount = VALUES(bi_amount),
                observed_time = VALUES(observed_time),
                deleted = 0,
                updated_time = VALUES(updated_time)
            """)
    int upsertReconciliationCurrent(
            @Param("tenantId") String tenantId,
            @Param("subjectCode") String subjectCode,
            @Param("subjectName") String subjectName,
            @Param("scopeCode") String scopeCode,
            @Param("scopeName") String scopeName,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("sourceRowCount") Long sourceRowCount,
            @Param("businessRowCount") Long businessRowCount,
            @Param("biRowCount") Long biRowCount,
            @Param("sourceAmount") BigDecimal sourceAmount,
            @Param("businessAmount") BigDecimal businessAmount,
            @Param("biAmount") BigDecimal biAmount,
            @Param("observedAt") LocalDateTime observedAt);

    @Select("""
            SELECT DISTINCT tenant_id
              FROM (
                    SELECT tenant_id FROM rigour_crm.crm_customer
                    UNION ALL
                    SELECT tenant_id FROM rigour_order.order_sales_order
                    UNION ALL
                    SELECT tenant_id FROM rigour_order.order_sales_order_line
                    UNION ALL
                    SELECT tenant_id FROM rigour_order.order_payment_record
                    UNION ALL
                    SELECT tenant_id FROM rigour_erp.erp_product
                    UNION ALL
                    SELECT tenant_id FROM rigour_erp.erp_stock_balance
                    UNION ALL
                    SELECT tenant_id FROM rigour_erp.erp_procurement_order
                    UNION ALL
                    SELECT tenant_id FROM rigour_erp.erp_stock_out_order
                    UNION ALL
                    SELECT tenant_id FROM bi_city_cost_record
                    UNION ALL
                    SELECT tenant_id FROM bi_customer_dim
                    UNION ALL
                    SELECT tenant_id FROM bi_product_dim
                    UNION ALL
                    SELECT tenant_id FROM bi_sales_order_fact
              ) tenants
             WHERE tenant_id IS NOT NULL
             ORDER BY tenant_id
            """)
    List<String> refreshTenantIds();

    @Insert("""
            INSERT IGNORE INTO bi_etl_checkpoint (
                tenant_id, source_code, source_name, status_code, locked_until, created_time, updated_time
            ) VALUES (
                #{tenantId}, #{lockCode}, #{lockName}, 'IDLE', NULL, #{now}, #{now}
            )
            """)
    int ensureRefreshLock(
            @Param("tenantId") String tenantId,
            @Param("lockCode") String lockCode,
            @Param("lockName") String lockName,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bi_etl_checkpoint
               SET status_code = 'RUNNING',
                   locked_until = #{lockedUntil},
                   failure_reason = NULL,
                   updated_time = #{now}
             WHERE tenant_id = #{tenantId}
               AND source_code = #{lockCode}
               AND (status_code <> 'RUNNING' OR locked_until IS NULL OR locked_until < #{now})
            """)
    int acquireRefreshLock(
            @Param("tenantId") String tenantId,
            @Param("lockCode") String lockCode,
            @Param("now") LocalDateTime now,
            @Param("lockedUntil") LocalDateTime lockedUntil);

    @Update("""
            UPDATE bi_etl_checkpoint
               SET status_code = #{statusCode},
                   locked_until = NULL,
                   failure_reason = #{failureReason},
                   updated_time = #{now}
             WHERE tenant_id = #{tenantId}
               AND source_code = #{lockCode}
            """)
    int releaseRefreshLock(
            @Param("tenantId") String tenantId,
            @Param("lockCode") String lockCode,
            @Param("statusCode") String statusCode,
            @Param("now") LocalDateTime now,
            @Param("failureReason") String failureReason);

    @Insert("""
            INSERT INTO bi_etl_run (
                tenant_id, job_code, status_code, started_time, pulled_count, upserted_count,
                skipped_count, created_time, updated_time
            ) VALUES (
                #{tenantId}, #{jobCode}, 'RUNNING', #{startedAt}, 0, 0, 0, #{startedAt}, #{startedAt}
            )
            """)
    int insertRefreshRun(
            @Param("tenantId") String tenantId,
            @Param("jobCode") String jobCode,
            @Param("startedAt") LocalDateTime startedAt);

    @Select("""
            SELECT id, tenant_id AS tenantId, job_code AS jobCode, status_code AS statusCode,
                   started_time AS startedTime, completed_time AS completedTime,
                   watermark_time AS watermarkTime, pulled_count AS pulledCount,
                   upserted_count AS upsertedCount, skipped_count AS skippedCount,
                   failure_reason AS failureReason
              FROM bi_etl_run
             WHERE tenant_id = #{tenantId}
               AND job_code = #{jobCode}
               AND started_time = #{startedAt}
             ORDER BY id DESC
             LIMIT 1
            """)
    Map<String, Object> refreshRunByStart(
            @Param("tenantId") String tenantId,
            @Param("jobCode") String jobCode,
            @Param("startedAt") LocalDateTime startedAt);

    @Select("""
            SELECT id, tenant_id AS tenantId, job_code AS jobCode, status_code AS statusCode,
                   started_time AS startedTime, completed_time AS completedTime,
                   watermark_time AS watermarkTime, pulled_count AS pulledCount,
                   upserted_count AS upsertedCount, skipped_count AS skippedCount,
                   failure_reason AS failureReason
              FROM bi_etl_run
             WHERE id = #{runId}
            """)
    Map<String, Object> refreshRun(@Param("runId") Long runId);

    @Update("""
            UPDATE bi_etl_run
               SET status_code = 'SUCCESS',
                   completed_time = #{completedAt},
                   watermark_time = #{watermarkTime},
                   pulled_count = #{pulledCount},
                   upserted_count = #{upsertedCount},
                   skipped_count = #{skippedCount},
                   failure_reason = NULL,
                   updated_time = #{completedAt}
             WHERE id = #{runId}
            """)
    int completeRefreshRun(
            @Param("runId") Long runId,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("watermarkTime") LocalDateTime watermarkTime,
            @Param("pulledCount") long pulledCount,
            @Param("upsertedCount") long upsertedCount,
            @Param("skippedCount") long skippedCount);

    @Update("""
            UPDATE bi_etl_run
               SET status_code = 'FAILED',
                   completed_time = #{completedAt},
                   pulled_count = #{pulledCount},
                   upserted_count = #{upsertedCount},
                   skipped_count = #{skippedCount},
                   failure_reason = #{failureReason},
                   updated_time = #{completedAt}
             WHERE id = #{runId}
            """)
    int failRefreshRun(
            @Param("runId") Long runId,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("pulledCount") long pulledCount,
            @Param("upsertedCount") long upsertedCount,
            @Param("skippedCount") long skippedCount,
            @Param("failureReason") String failureReason);

    @Select("""
            SELECT last_watermark_time AS watermarkTime
              FROM bi_etl_checkpoint
             WHERE tenant_id = #{tenantId}
               AND source_code = #{sourceCode}
             LIMIT 1
            """)
    Map<String, Object> checkpointWatermark(
            @Param("tenantId") String tenantId,
            @Param("sourceCode") String sourceCode);

    @Select("""
            SELECT COUNT(*)
              FROM rigour_crm.crm_customer
             WHERE tenant_id = #{tenantId}
            """)
    long crmCustomerSourceTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM bi_customer_dim
             WHERE tenant_id = #{tenantId}
            """)
    long biCustomerDimTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM rigour_order.order_sales_order
             WHERE tenant_id = #{tenantId}
            """)
    long orderSourceTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   COALESCE(SUM(payable_amount), 0) AS amount,
                   COUNT(DISTINCT NULLIF(COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')), '')) AS regionCount,
                   COUNT(DISTINCT COALESCE(NULLIF(o.owner_employee_code, ''),
                                           NULLIF(c.owner_employee_code, ''))) AS ownerCount,
                   COALESCE(SUM(CRC32(CONCAT_WS('|',
                       o.id,
                       COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''), ''),
                       COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, ''), ''),
                       COALESCE(o.customer_id, 0),
                       FORMAT(COALESCE(o.payable_amount, 0), 6),
                       FORMAT(COALESCE(o.paid_amount, 0), 6),
                       FORMAT(COALESCE(o.unpaid_amount, 0), 6),
                       COALESCE(o.order_status_code, ''),
                       COALESCE(o.payment_status_code, ''),
                       COALESCE(o.outbound_status_code, '')
                   ))), 0) AS rowSignature
              FROM rigour_order.order_sales_order o
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
             WHERE o.tenant_id = #{tenantId}
               AND o.deleted = 0
            """)
    Map<String, Object> orderSourceBackfillHealth(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM bi_sales_order_fact
             WHERE tenant_id = #{tenantId}
            """)
    long biSalesOrderFactTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   COALESCE(SUM(payable_amount), 0) AS amount,
                   COUNT(DISTINCT NULLIF(region_code, '')) AS regionCount,
                   COUNT(DISTINCT NULLIF(owner_staff_code, '')) AS ownerCount,
                   COALESCE(SUM(CRC32(CONCAT_WS('|',
                       order_id,
                       COALESCE(region_code, ''),
                       COALESCE(owner_staff_code, ''),
                       COALESCE(customer_id, 0),
                       FORMAT(COALESCE(payable_amount, 0), 6),
                       FORMAT(COALESCE(paid_amount, 0), 6),
                       FORMAT(COALESCE(unpaid_amount, 0), 6),
                       COALESCE(order_status_code, ''),
                       COALESCE(payment_status_code, ''),
                       COALESCE(outbound_status_code, '')
                   ))), 0) AS rowSignature
              FROM bi_sales_order_fact
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
            """)
    Map<String, Object> biSalesOrderFactBackfillHealth(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM rigour_order.order_sales_order_line
             WHERE tenant_id = #{tenantId}
            """)
    long orderLineSourceTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(DISTINCT l.id) AS rowCount,
                   COALESCE(SUM(l.line_amount), 0) AS amount,
                   COUNT(DISTINCT NULLIF(COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')), '')) AS regionCount,
                   COUNT(DISTINCT COALESCE(NULLIF(o.owner_employee_code, ''),
                                           NULLIF(c.owner_employee_code, ''))) AS ownerCount,
                   COALESCE(SUM(CRC32(CONCAT_WS('|',
                       l.id,
                       l.order_id,
                       COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''), ''),
                       COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, ''), ''),
                       COALESCE(l.product_id, 0),
                       COALESCE(l.product_variant_id, 0),
                       COALESCE(l.product_code_snapshot, ''),
                       COALESCE(l.sku_code_snapshot, ''),
                       FORMAT(COALESCE(l.quantity, 0), 6),
                       FORMAT(COALESCE(l.unit_price, 0), 6),
                       FORMAT(COALESCE(l.discount_amount, 0), 6),
                       FORMAT(COALESCE(l.line_amount, 0), 6)
                   ))), 0) AS rowSignature
              FROM rigour_order.order_sales_order_line l
              INNER JOIN rigour_order.order_sales_order o
                ON o.tenant_id = l.tenant_id AND o.id = l.order_id
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
             WHERE l.tenant_id = #{tenantId}
               AND l.deleted = 0
               AND o.deleted = 0
            """)
    Map<String, Object> orderLineSourceBackfillHealth(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM bi_sales_order_line_fact
             WHERE tenant_id = #{tenantId}
            """)
    long biSalesOrderLineFactTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   COALESCE(SUM(line_amount), 0) AS amount,
                   COUNT(DISTINCT NULLIF(region_code, '')) AS regionCount,
                   COUNT(DISTINCT NULLIF(owner_staff_code, '')) AS ownerCount,
                   COALESCE(SUM(CRC32(CONCAT_WS('|',
                       order_line_id,
                       order_id,
                       COALESCE(region_code, ''),
                       COALESCE(owner_staff_code, ''),
                       COALESCE(product_id, 0),
                       COALESCE(product_variant_id, 0),
                       COALESCE(product_code, ''),
                       COALESCE(sku_code, ''),
                       FORMAT(COALESCE(quantity, 0), 6),
                       FORMAT(COALESCE(unit_price, 0), 6),
                       FORMAT(COALESCE(discount_amount, 0), 6),
                       FORMAT(COALESCE(line_amount, 0), 6)
                   ))), 0) AS rowSignature
              FROM bi_sales_order_line_fact
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
            """)
    Map<String, Object> biSalesOrderLineFactBackfillHealth(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM rigour_erp.erp_product
             WHERE tenant_id = #{tenantId}
            """)
    long productSourceTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM bi_product_dim
             WHERE tenant_id = #{tenantId}
            """)
    long biProductDimTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM rigour_order.order_payment_record
             WHERE tenant_id = #{tenantId}
            """)
    long paymentSourceTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   COALESCE(SUM(p.paid_amount), 0) AS amount,
                   COUNT(DISTINCT NULLIF(COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')), '')) AS regionCount,
                   COUNT(DISTINCT COALESCE(NULLIF(o.owner_employee_code, ''),
                                           NULLIF(c.owner_employee_code, ''),
                                           NULLIF(p.collector_staff_code, ''))) AS ownerCount,
                   COALESCE(SUM(CRC32(CONCAT_WS('|',
                       p.id,
                       p.order_id,
                       COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''), ''),
                       COALESCE(NULLIF(o.owner_employee_code, ''), NULLIF(c.owner_employee_code, ''), ''),
                       COALESCE(p.collector_staff_code, ''),
                       COALESCE(p.customer_id, o.customer_id, 0),
                       FORMAT(COALESCE(p.paid_amount, 0), 6),
                       COALESCE(p.payment_method_code, '')
                   ))), 0) AS rowSignature
              FROM rigour_order.order_payment_record p
              LEFT JOIN rigour_order.order_sales_order o
                ON o.tenant_id = p.tenant_id AND o.id = p.order_id
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = p.tenant_id AND c.id = COALESCE(p.customer_id, o.customer_id)
             WHERE p.tenant_id = #{tenantId}
               AND p.deleted = 0
            """)
    Map<String, Object> paymentSourceBackfillHealth(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*)
              FROM bi_sales_payment_fact
             WHERE tenant_id = #{tenantId}
            """)
    long biSalesPaymentFactTotal(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   COALESCE(SUM(paid_amount), 0) AS amount,
                   COUNT(DISTINCT NULLIF(region_code, '')) AS regionCount,
                   COUNT(DISTINCT NULLIF(COALESCE(owner_staff_code, collector_staff_code), '')) AS ownerCount,
                   COALESCE(SUM(CRC32(CONCAT_WS('|',
                       payment_record_id,
                       order_id,
                       COALESCE(region_code, ''),
                       COALESCE(owner_staff_code, ''),
                       COALESCE(collector_staff_code, ''),
                       COALESCE(customer_id, 0),
                       FORMAT(COALESCE(paid_amount, 0), 6),
                       COALESCE(payment_method_code, '')
                   ))), 0) AS rowSignature
              FROM bi_sales_payment_fact
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
            """)
    Map<String, Object> biSalesPaymentFactBackfillHealth(@Param("tenantId") String tenantId);

    @Insert("""
            INSERT INTO bi_etl_checkpoint (
                tenant_id, source_code, source_name, last_watermark_time, last_success_time,
                last_run_id, status_code, failure_reason, locked_until, created_time, updated_time
            ) VALUES (
                #{tenantId}, #{sourceCode}, #{sourceName}, #{watermarkTime}, #{successTime},
                #{runId}, 'SUCCESS', NULL, NULL, #{successTime}, #{successTime}
            )
            ON DUPLICATE KEY UPDATE
                source_name = VALUES(source_name),
                last_watermark_time = VALUES(last_watermark_time),
                last_success_time = VALUES(last_success_time),
                last_run_id = VALUES(last_run_id),
                status_code = 'SUCCESS',
                failure_reason = NULL,
                updated_time = VALUES(updated_time)
            """)
    int updateCheckpoint(
            @Param("tenantId") String tenantId,
            @Param("sourceCode") String sourceCode,
            @Param("sourceName") String sourceName,
            @Param("watermarkTime") LocalDateTime watermarkTime,
            @Param("successTime") LocalDateTime successTime,
            @Param("runId") Long runId);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   MAX(GREATEST(c.updated_time,
                                COALESCE(ca.updated_time, c.updated_time),
                                COALESCE(ct.updated_time, c.updated_time),
                                COALESCE(contact.contactUpdatedTime, c.updated_time),
                                COALESCE(policy.updated_time, c.updated_time))) AS watermarkTime
              FROM rigour_crm.crm_customer c
              LEFT JOIN rigour_crm.crm_customer_area ca
                ON ca.tenant_id = UUID_TO_BIN(c.tenant_id) AND ca.area_code = c.region_code
              LEFT JOIN rigour_crm.crm_customer_type ct
                ON ct.tenant_id = UUID_TO_BIN(c.tenant_id) AND ct.type_code = c.customer_type_code
              LEFT JOIN (
                    SELECT tenant_id, party_id,
                           MAX(updated_time) AS contactUpdatedTime
                      FROM rigour_crm.crm_contact
                     WHERE deleted = 0
                       AND status = 'ACTIVE'
                     GROUP BY tenant_id, party_id
              ) contact ON contact.tenant_id = UUID_TO_BIN(c.tenant_id) AND contact.party_id = c.party_id
              LEFT JOIN rigour_crm.crm_customer_policy policy
                ON policy.tenant_id = UUID_TO_BIN(c.tenant_id)
               AND policy.party_id = c.party_id
               AND policy.deleted = 0
             WHERE c.tenant_id = #{tenantId}
               AND (
                    c.updated_time > #{from} AND c.updated_time <= #{to}
                    OR ca.updated_time > #{from} AND ca.updated_time <= #{to}
                    OR ct.updated_time > #{from} AND ct.updated_time <= #{to}
                    OR contact.contactUpdatedTime > #{from} AND contact.contactUpdatedTime <= #{to}
                    OR policy.updated_time > #{from} AND policy.updated_time <= #{to}
               )
            """)
    Map<String, Object> customerSourceSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Insert("""
            INSERT INTO bi_customer_dim (
                tenant_id, customer_id, customer_code, customer_name,
                contact_name_snapshot, contact_phone_snapshot, has_contact,
                region_code, region_name,
                owner_staff_code, owner_staff_name, customer_type_code, customer_type_name, status_code,
                payment_term_days,
                source_updated_time, synced_time, deleted, created_time, updated_time
            )
            SELECT c.tenant_id, c.id, c.customer_code, c.customer_name,
                   COALESCE(NULLIF(TRIM(c.contact_name), ''), contact.contactName),
                   COALESCE(NULLIF(TRIM(c.contact_phone), ''), contact.contactPhone),
                   CASE WHEN COALESCE(NULLIF(TRIM(c.contact_name), ''), NULLIF(TRIM(c.contact_phone), ''),
                                      NULLIF(contact.contactName, ''), NULLIF(contact.contactPhone, '')) IS NULL
                        THEN 0 ELSE 1 END,
                   c.region_code, ca.area_name,
                   NULLIF(c.owner_employee_code, ''),
                   COALESCE(c.owner_employee_name_snapshot, c.owner_sales_name),
                   c.customer_type_code, ct.type_name, c.status_code,
                   COALESCE(policy.payment_term_days, 30),
                   GREATEST(c.updated_time,
                            COALESCE(ca.updated_time, c.updated_time),
                            COALESCE(ct.updated_time, c.updated_time),
                            COALESCE(contact.contactUpdatedTime, c.updated_time),
                            COALESCE(policy.updated_time, c.updated_time)),
                   #{syncedAt}, c.deleted,
                   #{syncedAt}, #{syncedAt}
              FROM rigour_crm.crm_customer c
              LEFT JOIN rigour_crm.crm_customer_area ca
                ON ca.tenant_id = UUID_TO_BIN(c.tenant_id) AND ca.area_code = c.region_code
              LEFT JOIN rigour_crm.crm_customer_type ct
                ON ct.tenant_id = UUID_TO_BIN(c.tenant_id) AND ct.type_code = c.customer_type_code
              LEFT JOIN (
                    SELECT tenant_id, party_id,
                           COALESCE(
                               MAX(CASE WHEN is_primary = 1 THEN NULLIF(TRIM(contact_name), '') END),
                               MAX(NULLIF(TRIM(contact_name), ''))
                           ) AS contactName,
                           COALESCE(
                               MAX(CASE WHEN is_primary = 1 THEN NULLIF(TRIM(phone), '') END),
                               MAX(NULLIF(TRIM(phone), ''))
                           ) AS contactPhone,
                           MAX(updated_time) AS contactUpdatedTime
                      FROM rigour_crm.crm_contact
                     WHERE deleted = 0
                       AND status = 'ACTIVE'
                     GROUP BY tenant_id, party_id
              ) contact ON contact.tenant_id = UUID_TO_BIN(c.tenant_id) AND contact.party_id = c.party_id
              LEFT JOIN rigour_crm.crm_customer_policy policy
                ON policy.tenant_id = UUID_TO_BIN(c.tenant_id)
               AND policy.party_id = c.party_id
               AND policy.deleted = 0
             WHERE c.tenant_id = #{tenantId}
               AND (
                    c.updated_time > #{from} AND c.updated_time <= #{to}
                    OR ca.updated_time > #{from} AND ca.updated_time <= #{to}
                    OR ct.updated_time > #{from} AND ct.updated_time <= #{to}
                    OR contact.contactUpdatedTime > #{from} AND contact.contactUpdatedTime <= #{to}
                    OR policy.updated_time > #{from} AND policy.updated_time <= #{to}
               )
            ON DUPLICATE KEY UPDATE
                customer_code = VALUES(customer_code),
                customer_name = VALUES(customer_name),
                contact_name_snapshot = VALUES(contact_name_snapshot),
                contact_phone_snapshot = VALUES(contact_phone_snapshot),
                has_contact = VALUES(has_contact),
                region_code = VALUES(region_code),
                region_name = VALUES(region_name),
                owner_staff_code = VALUES(owner_staff_code),
                owner_staff_name = VALUES(owner_staff_name),
                customer_type_code = VALUES(customer_type_code),
                customer_type_name = VALUES(customer_type_name),
                status_code = VALUES(status_code),
                payment_term_days = VALUES(payment_term_days),
                source_updated_time = VALUES(source_updated_time),
                synced_time = VALUES(synced_time),
                deleted = VALUES(deleted),
                updated_time = VALUES(updated_time)
            """)
    int upsertCustomerDimFromSource(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Update("""
            UPDATE bi_customer_dim b
            INNER JOIN rigour_crm.crm_customer c
               ON c.tenant_id = b.tenant_id
              AND c.id = b.customer_id
            LEFT JOIN rigour_crm.crm_customer_area ca
               ON ca.tenant_id = UUID_TO_BIN(c.tenant_id)
              AND ca.area_code = c.region_code
            LEFT JOIN rigour_crm.crm_customer_type ct
               ON ct.tenant_id = UUID_TO_BIN(c.tenant_id)
              AND ct.type_code = c.customer_type_code
            LEFT JOIN (
                    SELECT tenant_id, party_id,
                           COALESCE(
                               MAX(CASE WHEN is_primary = 1 THEN NULLIF(TRIM(contact_name), '') END),
                               MAX(NULLIF(TRIM(contact_name), ''))
                           ) AS contactName,
                           COALESCE(
                               MAX(CASE WHEN is_primary = 1 THEN NULLIF(TRIM(phone), '') END),
                               MAX(NULLIF(TRIM(phone), ''))
                           ) AS contactPhone,
                           MAX(updated_time) AS contactUpdatedTime
                      FROM rigour_crm.crm_contact
                     WHERE deleted = 0
                       AND status = 'ACTIVE'
                     GROUP BY tenant_id, party_id
            ) contact ON contact.tenant_id = UUID_TO_BIN(c.tenant_id)
                     AND contact.party_id = c.party_id
            LEFT JOIN rigour_crm.crm_customer_policy policy
               ON policy.tenant_id = UUID_TO_BIN(c.tenant_id)
              AND policy.party_id = c.party_id
              AND policy.deleted = 0
               SET b.customer_code = c.customer_code,
                   b.customer_name = c.customer_name,
                   b.contact_name_snapshot = COALESCE(NULLIF(TRIM(c.contact_name), ''), contact.contactName),
                   b.contact_phone_snapshot = COALESCE(NULLIF(TRIM(c.contact_phone), ''), contact.contactPhone),
                   b.has_contact = CASE WHEN COALESCE(NULLIF(TRIM(c.contact_name), ''), NULLIF(TRIM(c.contact_phone), ''),
                                                      NULLIF(contact.contactName, ''), NULLIF(contact.contactPhone, '')) IS NULL
                                        THEN 0 ELSE 1 END,
                   b.region_code = c.region_code,
                   b.region_name = ca.area_name,
                   b.owner_staff_code = NULLIF(c.owner_employee_code, ''),
                   b.owner_staff_name = COALESCE(c.owner_employee_name_snapshot, c.owner_sales_name),
                   b.customer_type_code = c.customer_type_code,
                   b.customer_type_name = ct.type_name,
                   b.status_code = c.status_code,
                   b.payment_term_days = COALESCE(policy.payment_term_days, 30),
                   b.source_updated_time = GREATEST(c.updated_time,
                            COALESCE(ca.updated_time, c.updated_time),
                            COALESCE(ct.updated_time, c.updated_time),
                            COALESCE(contact.contactUpdatedTime, c.updated_time),
                            COALESCE(policy.updated_time, c.updated_time)),
                   b.synced_time = #{syncedAt},
                   b.deleted = c.deleted,
                   b.updated_time = #{syncedAt}
             WHERE b.tenant_id = #{tenantId}
               AND (
                    COALESCE(b.customer_code, '') != COALESCE(c.customer_code, '')
                    OR COALESCE(b.customer_name, '') != COALESCE(c.customer_name, '')
                    OR COALESCE(b.contact_name_snapshot, '') != COALESCE(COALESCE(NULLIF(TRIM(c.contact_name), ''), contact.contactName), '')
                    OR COALESCE(b.contact_phone_snapshot, '') != COALESCE(COALESCE(NULLIF(TRIM(c.contact_phone), ''), contact.contactPhone), '')
                    OR b.has_contact != CASE WHEN COALESCE(NULLIF(TRIM(c.contact_name), ''), NULLIF(TRIM(c.contact_phone), ''),
                                                          NULLIF(contact.contactName, ''), NULLIF(contact.contactPhone, '')) IS NULL
                                             THEN 0 ELSE 1 END
                    OR COALESCE(b.region_code, '') != COALESCE(c.region_code, '')
                    OR COALESCE(b.region_name, '') != COALESCE(ca.area_name, '')
                    OR COALESCE(b.owner_staff_code, '') != COALESCE(NULLIF(c.owner_employee_code, ''), '')
                    OR COALESCE(b.owner_staff_name, '') != COALESCE(COALESCE(c.owner_employee_name_snapshot, c.owner_sales_name), '')
                    OR COALESCE(b.customer_type_code, '') != COALESCE(c.customer_type_code, '')
                    OR COALESCE(b.customer_type_name, '') != COALESCE(ct.type_name, '')
                    OR COALESCE(b.status_code, '') != COALESCE(c.status_code, '')
                    OR b.payment_term_days != COALESCE(policy.payment_term_days, 30)
                    OR b.deleted != c.deleted
               )
            """)
    int backfillCustomerContactSnapshots(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   MAX(GREATEST(p.updated_time,
                                COALESCE(pc.updated_time, p.updated_time),
                                COALESCE(b.updated_time, p.updated_time))) AS watermarkTime
              FROM rigour_erp.erp_product p
              LEFT JOIN rigour_erp.erp_product_category pc
                ON pc.tenant_id = p.tenant_id AND pc.id = p.category_id
              LEFT JOIN rigour_erp.erp_product_brand b
                ON b.tenant_id = p.tenant_id AND b.id = p.brand_id
             WHERE p.tenant_id = #{tenantId}
               AND (
                    p.updated_time > #{from} AND p.updated_time <= #{to}
                    OR pc.updated_time > #{from} AND pc.updated_time <= #{to}
                    OR b.updated_time > #{from} AND b.updated_time <= #{to}
               )
            """)
    Map<String, Object> productSourceSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Insert("""
            INSERT INTO bi_product_dim (
                tenant_id, product_id, product_code, product_name,
                product_category_id, product_category_code, product_category_name,
                brand_id, brand_code, brand_name,
                shelf_status_code, submit_status_code,
                source_updated_time, synced_time, deleted, created_time, updated_time
            )
            SELECT p.tenant_id, p.id, p.product_code, p.product_name,
                   p.category_id, pc.category_code, pc.category_name,
                   p.brand_id, b.brand_code, b.brand_name,
                   p.shelf_status_code, p.submit_status_code,
                   GREATEST(p.updated_time,
                            COALESCE(pc.updated_time, p.updated_time),
                            COALESCE(b.updated_time, p.updated_time)),
                   #{syncedAt}, p.deleted, #{syncedAt}, #{syncedAt}
              FROM rigour_erp.erp_product p
              LEFT JOIN rigour_erp.erp_product_category pc
                ON pc.tenant_id = p.tenant_id AND pc.id = p.category_id
              LEFT JOIN rigour_erp.erp_product_brand b
                ON b.tenant_id = p.tenant_id AND b.id = p.brand_id
             WHERE p.tenant_id = #{tenantId}
               AND (
                    p.updated_time > #{from} AND p.updated_time <= #{to}
                    OR pc.updated_time > #{from} AND pc.updated_time <= #{to}
                    OR b.updated_time > #{from} AND b.updated_time <= #{to}
               )
            ON DUPLICATE KEY UPDATE
                product_code = VALUES(product_code),
                product_name = VALUES(product_name),
                product_category_id = VALUES(product_category_id),
                product_category_code = VALUES(product_category_code),
                product_category_name = VALUES(product_category_name),
                brand_id = VALUES(brand_id),
                brand_code = VALUES(brand_code),
                brand_name = VALUES(brand_name),
                shelf_status_code = VALUES(shelf_status_code),
                submit_status_code = VALUES(submit_status_code),
                source_updated_time = VALUES(source_updated_time),
                synced_time = VALUES(synced_time),
                deleted = VALUES(deleted),
                updated_time = VALUES(updated_time)
            """)
    int upsertProductDimFromSource(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   MAX(GREATEST(o.updated_time,
                                COALESCE(c.updated_time, o.updated_time),
                                COALESCE(ca.updated_time, o.updated_time),
                                COALESCE(ct.updated_time, o.updated_time),
                                COALESCE(policy.updated_time, o.updated_time))) AS watermarkTime
              FROM rigour_order.order_sales_order o
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
              LEFT JOIN rigour_crm.crm_customer_area ca
                ON ca.tenant_id = UUID_TO_BIN(o.tenant_id) AND ca.area_code = COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''))
              LEFT JOIN rigour_crm.crm_customer_type ct
                ON ct.tenant_id = UUID_TO_BIN(o.tenant_id) AND ct.type_code = c.customer_type_code
              LEFT JOIN rigour_crm.crm_customer_policy policy
                ON policy.tenant_id = UUID_TO_BIN(o.tenant_id)
               AND policy.party_id = c.party_id
               AND policy.deleted = 0
             WHERE o.tenant_id = #{tenantId}
               AND (
                    o.updated_time > #{from} AND o.updated_time <= #{to}
                    OR c.updated_time > #{from} AND c.updated_time <= #{to}
                    OR ca.updated_time > #{from} AND ca.updated_time <= #{to}
                    OR ct.updated_time > #{from} AND ct.updated_time <= #{to}
                    OR policy.updated_time > #{from} AND policy.updated_time <= #{to}
               )
            """)
    Map<String, Object> orderSourceSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Insert("""
            INSERT INTO bi_sales_order_fact (
                tenant_id, order_id, order_no, source_system_code, source_order_no,
                customer_id, customer_code, customer_name, customer_type_code, customer_type_name, customer_status_code,
                region_code, region_name, owner_staff_code, owner_staff_name, order_date, payment_time, shipment_time,
                payment_term_days, payment_due_date,
                order_status_code, payment_status_code, outbound_status_code, order_type_code,
                total_quantity, payable_amount, paid_amount, unpaid_amount,
                source_updated_time, synced_time, deleted, created_time, updated_time
            )
            SELECT o.tenant_id, o.id, o.order_no, COALESCE(o.source_system_code, 'MANUAL'), o.source_order_no,
                   o.customer_id, COALESCE(o.customer_code_snapshot, c.customer_code),
                   COALESCE(o.customer_name_snapshot, c.customer_name), c.customer_type_code, ct.type_name, c.status_code,
                   COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')), ca.area_name,
                   COALESCE(NULLIF(o.owner_employee_code, ''),
                            NULLIF(c.owner_employee_code, '')),
                   COALESCE(o.owner_employee_name_snapshot, o.owner_sales_name, c.owner_employee_name_snapshot, c.owner_sales_name),
                   o.order_date, o.payment_time, o.shipment_time,
                   COALESCE(policy.payment_term_days, 30),
                   DATE_ADD(o.order_date, INTERVAL COALESCE(policy.payment_term_days, 30) DAY),
                   o.order_status_code, o.payment_status_code, o.outbound_status_code, o.order_type_code,
                   o.total_quantity, o.payable_amount, o.paid_amount, o.unpaid_amount,
                   GREATEST(o.updated_time,
                            COALESCE(c.updated_time, o.updated_time),
                            COALESCE(ca.updated_time, o.updated_time),
                            COALESCE(ct.updated_time, o.updated_time),
                            COALESCE(policy.updated_time, o.updated_time)),
                   #{syncedAt}, o.deleted, #{syncedAt}, #{syncedAt}
              FROM rigour_order.order_sales_order o
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
              LEFT JOIN rigour_crm.crm_customer_area ca
                ON ca.tenant_id = UUID_TO_BIN(o.tenant_id) AND ca.area_code = COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''))
              LEFT JOIN rigour_crm.crm_customer_type ct
                ON ct.tenant_id = UUID_TO_BIN(o.tenant_id) AND ct.type_code = c.customer_type_code
              LEFT JOIN rigour_crm.crm_customer_policy policy
                ON policy.tenant_id = UUID_TO_BIN(o.tenant_id)
               AND policy.party_id = c.party_id
               AND policy.deleted = 0
             WHERE o.tenant_id = #{tenantId}
               AND (
                    o.updated_time > #{from} AND o.updated_time <= #{to}
                    OR c.updated_time > #{from} AND c.updated_time <= #{to}
                    OR ca.updated_time > #{from} AND ca.updated_time <= #{to}
                    OR ct.updated_time > #{from} AND ct.updated_time <= #{to}
                    OR policy.updated_time > #{from} AND policy.updated_time <= #{to}
               )
            ON DUPLICATE KEY UPDATE
                order_no = VALUES(order_no),
                source_system_code = VALUES(source_system_code),
                source_order_no = VALUES(source_order_no),
                customer_id = VALUES(customer_id),
                customer_code = VALUES(customer_code),
                customer_name = VALUES(customer_name),
                customer_type_code = VALUES(customer_type_code),
                customer_type_name = VALUES(customer_type_name),
                customer_status_code = VALUES(customer_status_code),
                region_code = VALUES(region_code),
                region_name = VALUES(region_name),
                owner_staff_code = VALUES(owner_staff_code),
                owner_staff_name = VALUES(owner_staff_name),
                order_date = VALUES(order_date),
                payment_time = VALUES(payment_time),
                shipment_time = VALUES(shipment_time),
                payment_term_days = VALUES(payment_term_days),
                payment_due_date = VALUES(payment_due_date),
                order_status_code = VALUES(order_status_code),
                payment_status_code = VALUES(payment_status_code),
                outbound_status_code = VALUES(outbound_status_code),
                order_type_code = VALUES(order_type_code),
                total_quantity = VALUES(total_quantity),
                payable_amount = VALUES(payable_amount),
                paid_amount = VALUES(paid_amount),
                unpaid_amount = VALUES(unpaid_amount),
                source_updated_time = VALUES(source_updated_time),
                synced_time = VALUES(synced_time),
                deleted = VALUES(deleted),
                updated_time = VALUES(updated_time)
            """)
    int upsertSalesOrderFactFromSource(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Select("""
            SELECT COUNT(DISTINCT l.id) AS rowCount,
                   MAX(GREATEST(l.updated_time, COALESCE(o.updated_time, l.updated_time),
                                COALESCE(c.updated_time, l.updated_time), COALESCE(p.updated_time, l.updated_time),
                                COALESCE(v.updated_time, l.updated_time),
                                COALESCE(b.updated_time, l.updated_time),
                                COALESCE(r.updated_time, l.updated_time),
                                COALESCE(pc.updated_time, l.updated_time),
                                COALESCE(ca.updated_time, l.updated_time),
                                COALESCE(ct.updated_time, l.updated_time))) AS watermarkTime
              FROM rigour_order.order_sales_order_line l
              INNER JOIN rigour_order.order_sales_order o
                ON o.tenant_id = l.tenant_id AND o.id = l.order_id
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
              LEFT JOIN rigour_crm.crm_customer_area ca
                ON ca.tenant_id = UUID_TO_BIN(o.tenant_id) AND ca.area_code = COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''))
              LEFT JOIN rigour_crm.crm_customer_type ct
                ON ct.tenant_id = UUID_TO_BIN(o.tenant_id) AND ct.type_code = c.customer_type_code
              LEFT JOIN rigour_erp.erp_product p
                ON p.tenant_id = l.tenant_id AND p.id = l.product_id
              LEFT JOIN rigour_erp.erp_product_variant v
                ON v.tenant_id = l.tenant_id AND v.id = l.product_variant_id
              LEFT JOIN rigour_erp.erp_product_brand b
                ON b.tenant_id = p.tenant_id AND b.id = p.brand_id
              LEFT JOIN rigour_erp.erp_product_category pc
                ON pc.tenant_id = p.tenant_id AND pc.id = p.category_id
              LEFT JOIN rigour_order.order_refund_record r
                ON r.tenant_id = o.tenant_id AND r.order_id = o.id
             WHERE l.tenant_id = #{tenantId}
               AND (
                    l.updated_time > #{from} AND l.updated_time <= #{to}
                    OR o.updated_time > #{from} AND o.updated_time <= #{to}
                    OR c.updated_time > #{from} AND c.updated_time <= #{to}
                    OR p.updated_time > #{from} AND p.updated_time <= #{to}
                    OR v.updated_time > #{from} AND v.updated_time <= #{to}
                    OR b.updated_time > #{from} AND b.updated_time <= #{to}
                    OR r.updated_time > #{from} AND r.updated_time <= #{to}
                    OR pc.updated_time > #{from} AND pc.updated_time <= #{to}
                    OR ca.updated_time > #{from} AND ca.updated_time <= #{to}
                    OR ct.updated_time > #{from} AND ct.updated_time <= #{to}
               )
            """)
    Map<String, Object> orderLineSourceSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Insert("""
            INSERT INTO bi_sales_order_line_fact (
                tenant_id, order_line_id, order_id, order_no, source_system_code, source_order_no,
                customer_id, customer_code, customer_name, customer_type_code, customer_type_name, customer_status_code,
                region_code, region_name, owner_staff_code, owner_staff_name, order_date, payment_time, shipment_time,
                order_status_code, payment_status_code, outbound_status_code, order_type_code,
                product_id, product_variant_id, product_code, sku_code, product_name,
                product_category_id, product_category_code, product_category_name,
                brand_id, brand_code, brand_name,
                specification_snapshot, unit_code, quantity, unit_price, discount_rate,
                discount_amount, line_amount, refund_amount, sales_net_amount,
                estimated_unit_cost, estimated_cost_amount, estimated_gross_profit_amount,
                estimated_gross_profit_rate, cost_covered,
                source_updated_time, synced_time, deleted,
                created_time, updated_time
            )
            SELECT l.tenant_id, l.id, l.order_id, o.order_no, COALESCE(o.source_system_code, 'MANUAL'), o.source_order_no,
                   o.customer_id, COALESCE(o.customer_code_snapshot, c.customer_code),
                   COALESCE(o.customer_name_snapshot, c.customer_name), c.customer_type_code, ct.type_name, c.status_code,
                   COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')), ca.area_name,
                   COALESCE(NULLIF(o.owner_employee_code, ''),
                            NULLIF(c.owner_employee_code, '')),
                   COALESCE(o.owner_employee_name_snapshot, o.owner_sales_name, c.owner_employee_name_snapshot, c.owner_sales_name),
                   o.order_date, o.payment_time, o.shipment_time,
                   o.order_status_code, o.payment_status_code, o.outbound_status_code, o.order_type_code,
                   l.product_id, l.product_variant_id, COALESCE(l.product_code_snapshot, p.product_code),
                   l.sku_code_snapshot, COALESCE(l.product_name_snapshot, p.product_name),
                   p.category_id, pc.category_code, pc.category_name,
                   p.brand_id, b.brand_code, b.brand_name,
                   l.specification_snapshot, l.unit_code,
                   l.quantity, l.unit_price, l.discount_rate, l.discount_amount, l.line_amount,
                   CASE WHEN COALESCE(ls.orderLineAmount, 0) = 0 THEN 0
                        ELSE COALESCE(rs.refundAmount, 0) * COALESCE(l.line_amount, 0) / ls.orderLineAmount END,
                   COALESCE(l.line_amount, 0) - CASE WHEN COALESCE(ls.orderLineAmount, 0) = 0 THEN 0
                        ELSE COALESCE(rs.refundAmount, 0) * COALESCE(l.line_amount, 0) / ls.orderLineAmount END,
                   COALESCE(v.purchase_price, 0),
                   COALESCE(l.quantity, 0) * COALESCE(v.purchase_price, 0),
                   COALESCE(l.line_amount, 0) - CASE WHEN COALESCE(ls.orderLineAmount, 0) = 0 THEN 0
                        ELSE COALESCE(rs.refundAmount, 0) * COALESCE(l.line_amount, 0) / ls.orderLineAmount END
                        - COALESCE(l.quantity, 0) * COALESCE(v.purchase_price, 0),
                   CASE WHEN COALESCE(l.line_amount, 0) - CASE WHEN COALESCE(ls.orderLineAmount, 0) = 0 THEN 0
                                  ELSE COALESCE(rs.refundAmount, 0) * COALESCE(l.line_amount, 0) / ls.orderLineAmount END = 0 THEN 0
                        ELSE (
                            COALESCE(l.line_amount, 0) - CASE WHEN COALESCE(ls.orderLineAmount, 0) = 0 THEN 0
                                  ELSE COALESCE(rs.refundAmount, 0) * COALESCE(l.line_amount, 0) / ls.orderLineAmount END
                                  - COALESCE(l.quantity, 0) * COALESCE(v.purchase_price, 0)
                        ) / (
                            COALESCE(l.line_amount, 0) - CASE WHEN COALESCE(ls.orderLineAmount, 0) = 0 THEN 0
                                  ELSE COALESCE(rs.refundAmount, 0) * COALESCE(l.line_amount, 0) / ls.orderLineAmount END
                        ) * 100 END,
                   CASE WHEN v.purchase_price IS NOT NULL AND v.purchase_price > 0 THEN 1 ELSE 0 END,
                   GREATEST(l.updated_time, COALESCE(o.updated_time, l.updated_time),
                            COALESCE(c.updated_time, l.updated_time), COALESCE(p.updated_time, l.updated_time),
                            COALESCE(v.updated_time, l.updated_time),
                            COALESCE(b.updated_time, l.updated_time),
                            COALESCE(rs.refundUpdatedTime, l.updated_time),
                            COALESCE(pc.updated_time, l.updated_time),
                            COALESCE(ca.updated_time, l.updated_time),
                            COALESCE(ct.updated_time, l.updated_time)),
                   #{syncedAt},
                   CASE WHEN COALESCE(l.deleted, 0) <> 0 OR COALESCE(o.deleted, 0) <> 0
                        THEN 1 ELSE 0 END,
                   #{syncedAt}, #{syncedAt}
              FROM rigour_order.order_sales_order_line l
              INNER JOIN rigour_order.order_sales_order o
                ON o.tenant_id = l.tenant_id AND o.id = l.order_id
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
              LEFT JOIN rigour_crm.crm_customer_area ca
                ON ca.tenant_id = UUID_TO_BIN(o.tenant_id) AND ca.area_code = COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''))
              LEFT JOIN rigour_crm.crm_customer_type ct
                ON ct.tenant_id = UUID_TO_BIN(o.tenant_id) AND ct.type_code = c.customer_type_code
              LEFT JOIN rigour_erp.erp_product p
                ON p.tenant_id = l.tenant_id AND p.id = l.product_id
              LEFT JOIN rigour_erp.erp_product_variant v
                ON v.tenant_id = l.tenant_id AND v.id = l.product_variant_id
              LEFT JOIN rigour_erp.erp_product_brand b
                ON b.tenant_id = p.tenant_id AND b.id = p.brand_id
              LEFT JOIN rigour_erp.erp_product_category pc
                ON pc.tenant_id = p.tenant_id AND pc.id = p.category_id
              LEFT JOIN (
                    SELECT tenant_id, order_id, COALESCE(SUM(refund_amount), 0) AS refundAmount,
                           MAX(updated_time) AS refundUpdatedTime
                      FROM rigour_order.order_refund_record
                     WHERE deleted = 0
                       AND refund_status_code <> 'CANCELLED'
                     GROUP BY tenant_id, order_id
              ) rs ON rs.tenant_id = o.tenant_id AND rs.order_id = o.id
              LEFT JOIN (
                    SELECT tenant_id, order_id, COALESCE(SUM(line_amount), 0) AS orderLineAmount
                      FROM rigour_order.order_sales_order_line
                     WHERE deleted = 0
                     GROUP BY tenant_id, order_id
              ) ls ON ls.tenant_id = l.tenant_id AND ls.order_id = l.order_id
             WHERE l.tenant_id = #{tenantId}
               AND (
                    l.updated_time > #{from} AND l.updated_time <= #{to}
                    OR o.updated_time > #{from} AND o.updated_time <= #{to}
                    OR c.updated_time > #{from} AND c.updated_time <= #{to}
                    OR p.updated_time > #{from} AND p.updated_time <= #{to}
                    OR v.updated_time > #{from} AND v.updated_time <= #{to}
                    OR b.updated_time > #{from} AND b.updated_time <= #{to}
                    OR rs.refundUpdatedTime > #{from} AND rs.refundUpdatedTime <= #{to}
                    OR pc.updated_time > #{from} AND pc.updated_time <= #{to}
                    OR ca.updated_time > #{from} AND ca.updated_time <= #{to}
                    OR ct.updated_time > #{from} AND ct.updated_time <= #{to}
               )
            ON DUPLICATE KEY UPDATE
                order_id = VALUES(order_id),
                order_no = VALUES(order_no),
                source_system_code = VALUES(source_system_code),
                source_order_no = VALUES(source_order_no),
                customer_id = VALUES(customer_id),
                customer_code = VALUES(customer_code),
                customer_name = VALUES(customer_name),
                customer_type_code = VALUES(customer_type_code),
                customer_type_name = VALUES(customer_type_name),
                customer_status_code = VALUES(customer_status_code),
                region_code = VALUES(region_code),
                region_name = VALUES(region_name),
                owner_staff_code = VALUES(owner_staff_code),
                owner_staff_name = VALUES(owner_staff_name),
                order_date = VALUES(order_date),
                payment_time = VALUES(payment_time),
                shipment_time = VALUES(shipment_time),
                order_status_code = VALUES(order_status_code),
                payment_status_code = VALUES(payment_status_code),
                outbound_status_code = VALUES(outbound_status_code),
                order_type_code = VALUES(order_type_code),
                product_id = VALUES(product_id),
                product_variant_id = VALUES(product_variant_id),
                product_code = VALUES(product_code),
                sku_code = VALUES(sku_code),
                product_name = VALUES(product_name),
                product_category_id = VALUES(product_category_id),
                product_category_code = VALUES(product_category_code),
                product_category_name = VALUES(product_category_name),
                brand_id = VALUES(brand_id),
                brand_code = VALUES(brand_code),
                brand_name = VALUES(brand_name),
                specification_snapshot = VALUES(specification_snapshot),
                unit_code = VALUES(unit_code),
                quantity = VALUES(quantity),
                unit_price = VALUES(unit_price),
                discount_rate = VALUES(discount_rate),
                discount_amount = VALUES(discount_amount),
                line_amount = VALUES(line_amount),
                refund_amount = VALUES(refund_amount),
                sales_net_amount = VALUES(sales_net_amount),
                estimated_unit_cost = VALUES(estimated_unit_cost),
                estimated_cost_amount = VALUES(estimated_cost_amount),
                estimated_gross_profit_amount = VALUES(estimated_gross_profit_amount),
                estimated_gross_profit_rate = VALUES(estimated_gross_profit_rate),
                cost_covered = VALUES(cost_covered),
                source_updated_time = VALUES(source_updated_time),
                synced_time = VALUES(synced_time),
                deleted = VALUES(deleted),
                updated_time = VALUES(updated_time)
            """)
    int upsertSalesOrderLineFactFromSource(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   MAX(GREATEST(p.updated_time, COALESCE(o.updated_time, p.updated_time),
                                COALESCE(c.updated_time, p.updated_time),
                                COALESCE(ca.updated_time, p.updated_time),
                                COALESCE(ct.updated_time, p.updated_time))) AS watermarkTime
              FROM rigour_order.order_payment_record p
              LEFT JOIN rigour_order.order_sales_order o
                ON o.tenant_id = p.tenant_id AND o.id = p.order_id
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = p.tenant_id AND c.id = COALESCE(p.customer_id, o.customer_id)
              LEFT JOIN rigour_crm.crm_customer_area ca
                ON ca.tenant_id = UUID_TO_BIN(p.tenant_id) AND ca.area_code = COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''))
              LEFT JOIN rigour_crm.crm_customer_type ct
                ON ct.tenant_id = UUID_TO_BIN(p.tenant_id) AND ct.type_code = c.customer_type_code
             WHERE p.tenant_id = #{tenantId}
               AND (
                    p.updated_time > #{from} AND p.updated_time <= #{to}
                    OR o.updated_time > #{from} AND o.updated_time <= #{to}
                    OR c.updated_time > #{from} AND c.updated_time <= #{to}
                    OR ca.updated_time > #{from} AND ca.updated_time <= #{to}
                    OR ct.updated_time > #{from} AND ct.updated_time <= #{to}
               )
            """)
    Map<String, Object> paymentSourceSummary(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Insert("""
            INSERT INTO bi_sales_payment_fact (
                tenant_id, payment_id, payment_no, order_id, order_no, source_system_code,
                customer_id, customer_code, customer_name, customer_type_code, customer_type_name, region_code, region_name,
                owner_staff_code, owner_staff_name, collector_staff_code, collector_staff_name,
                payment_time, payment_method_code, paid_amount, source_updated_time, synced_time,
                deleted, created_time, updated_time
            )
            SELECT p.tenant_id, p.id, p.payment_no, p.order_id, COALESCE(p.sales_order_no_snapshot, o.order_no),
                   COALESCE(o.source_system_code, 'MANUAL'),
                   COALESCE(p.customer_id, o.customer_id),
                   COALESCE(p.customer_code_snapshot, o.customer_code_snapshot, c.customer_code),
                   COALESCE(p.customer_name_snapshot, o.customer_name_snapshot, c.customer_name),
                   c.customer_type_code, ct.type_name,
                   COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, '')), ca.area_name,
                   COALESCE(NULLIF(o.owner_employee_code, ''),
                            NULLIF(c.owner_employee_code, ''),
                            NULLIF(p.collector_staff_code, '')),
                   COALESCE(o.owner_employee_name_snapshot, o.owner_sales_name, c.owner_employee_name_snapshot, c.owner_sales_name),
                   p.collector_staff_code, p.collector_name_snapshot,
                   p.payment_time, p.payment_method_code, p.paid_amount,
                   GREATEST(p.updated_time, COALESCE(o.updated_time, p.updated_time),
                            COALESCE(c.updated_time, p.updated_time),
                            COALESCE(ca.updated_time, p.updated_time),
                            COALESCE(ct.updated_time, p.updated_time)),
                   #{syncedAt}, p.deleted, #{syncedAt}, #{syncedAt}
              FROM rigour_order.order_payment_record p
              LEFT JOIN rigour_order.order_sales_order o
                ON o.tenant_id = p.tenant_id AND o.id = p.order_id
              LEFT JOIN rigour_crm.crm_customer c
                ON c.tenant_id = p.tenant_id AND c.id = COALESCE(p.customer_id, o.customer_id)
              LEFT JOIN rigour_crm.crm_customer_area ca
                ON ca.tenant_id = UUID_TO_BIN(p.tenant_id) AND ca.area_code = COALESCE(NULLIF(o.region_code, ''), NULLIF(c.region_code, ''))
              LEFT JOIN rigour_crm.crm_customer_type ct
                ON ct.tenant_id = UUID_TO_BIN(p.tenant_id) AND ct.type_code = c.customer_type_code
             WHERE p.tenant_id = #{tenantId}
               AND (
                    p.updated_time > #{from} AND p.updated_time <= #{to}
                    OR o.updated_time > #{from} AND o.updated_time <= #{to}
                    OR c.updated_time > #{from} AND c.updated_time <= #{to}
                    OR ca.updated_time > #{from} AND ca.updated_time <= #{to}
                    OR ct.updated_time > #{from} AND ct.updated_time <= #{to}
               )
            ON DUPLICATE KEY UPDATE
                payment_no = VALUES(payment_no),
                order_id = VALUES(order_id),
                order_no = VALUES(order_no),
                source_system_code = VALUES(source_system_code),
                customer_id = VALUES(customer_id),
                customer_code = VALUES(customer_code),
                customer_name = VALUES(customer_name),
                customer_type_code = VALUES(customer_type_code),
                customer_type_name = VALUES(customer_type_name),
                region_code = VALUES(region_code),
                region_name = VALUES(region_name),
                owner_staff_code = VALUES(owner_staff_code),
                owner_staff_name = VALUES(owner_staff_name),
                collector_staff_code = VALUES(collector_staff_code),
                collector_staff_name = VALUES(collector_staff_name),
                payment_time = VALUES(payment_time),
                payment_method_code = VALUES(payment_method_code),
                paid_amount = VALUES(paid_amount),
                source_updated_time = VALUES(source_updated_time),
                synced_time = VALUES(synced_time),
                deleted = VALUES(deleted),
                updated_time = VALUES(updated_time)
            """)
    int upsertSalesPaymentFactFromSource(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Update("""
            UPDATE bi_sales_order_fact o
            INNER JOIN bi_customer_dim c
               ON c.tenant_id = o.tenant_id
              AND c.customer_id = o.customer_id
              AND c.deleted = 0
              AND c.region_code IS NOT NULL
              AND c.region_code <> ''
               SET o.region_code = c.region_code,
                   o.region_name = c.region_name,
                   o.synced_time = #{syncedAt},
                   o.updated_time = #{syncedAt}
             WHERE o.tenant_id = #{tenantId}
               AND (
                    o.region_code IS NULL
                    OR o.region_code = ''
               )
            """)
    int backfillOrderFactCustomerRegion(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Update("""
            UPDATE bi_sales_order_line_fact l
            INNER JOIN bi_customer_dim c
               ON c.tenant_id = l.tenant_id
              AND c.customer_id = l.customer_id
              AND c.deleted = 0
              AND c.region_code IS NOT NULL
              AND c.region_code <> ''
               SET l.region_code = c.region_code,
                   l.region_name = c.region_name,
                   l.synced_time = #{syncedAt},
                   l.updated_time = #{syncedAt}
             WHERE l.tenant_id = #{tenantId}
               AND (
                    l.region_code IS NULL
                    OR l.region_code = ''
               )
            """)
    int backfillOrderLineFactCustomerRegion(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Update("""
            UPDATE bi_sales_payment_fact p
            INNER JOIN bi_customer_dim c
               ON c.tenant_id = p.tenant_id
              AND c.customer_id = p.customer_id
              AND c.deleted = 0
              AND c.region_code IS NOT NULL
              AND c.region_code <> ''
               SET p.region_code = c.region_code,
                   p.region_name = c.region_name,
                   p.synced_time = #{syncedAt},
                   p.updated_time = #{syncedAt}
             WHERE p.tenant_id = #{tenantId}
               AND (
                    p.region_code IS NULL
                    OR p.region_code = ''
               )
            """)
    int backfillPaymentFactCustomerRegion(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Select("""
            SELECT COUNT(*) AS rowCount,
                   MAX(GREATEST(b.updated_time, COALESCE(w.updated_time, b.updated_time),
                                COALESCE(p.updated_time, b.updated_time), COALESCE(v.updated_time, b.updated_time))) AS watermarkTime
              FROM rigour_erp.erp_stock_balance b
              LEFT JOIN rigour_erp.erp_inventory_warehouse w
                ON w.tenant_id = b.tenant_id AND w.id = b.warehouse_id
              LEFT JOIN rigour_erp.erp_product p
                ON p.tenant_id = b.tenant_id AND p.id = b.product_id
              LEFT JOIN rigour_erp.erp_product_variant v
                ON v.tenant_id = b.tenant_id AND v.id = b.product_variant_id
             WHERE b.tenant_id = #{tenantId}
            """)
    Map<String, Object> inventorySourceSummary(@Param("tenantId") String tenantId);

    @Update("""
            UPDATE bi_inventory_balance_current
               SET deleted = 1,
                   synced_time = #{syncedAt},
                   updated_time = #{syncedAt}
             WHERE tenant_id = #{tenantId}
            """)
    int markInventorySnapshotDeleted(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Insert("""
            INSERT INTO bi_inventory_balance_current (
                tenant_id, warehouse_id, warehouse_code, warehouse_name, region_code,
                product_id, product_code, product_name, product_category_id,
                product_variant_id, variant_code, unit_code, specification_snapshot,
                available_quantity, locked_quantity, in_transit_quantity,
                source_updated_time, synced_time, deleted, created_time, updated_time
            )
            SELECT b.tenant_id, b.warehouse_id, w.warehouse_code, w.warehouse_name, w.region_code,
                   b.product_id, p.product_code, p.product_name, p.category_id,
                   b.product_variant_id, v.variant_code, v.unit_code, v.specification_snapshot,
                   b.available_quantity, b.locked_quantity, b.in_transit_quantity,
                   GREATEST(b.updated_time, COALESCE(w.updated_time, b.updated_time),
                            COALESCE(p.updated_time, b.updated_time), COALESCE(v.updated_time, b.updated_time)),
                   #{syncedAt},
                   CASE WHEN COALESCE(b.deleted, 0) <> 0
                             OR w.id IS NULL OR p.id IS NULL OR v.id IS NULL
                             OR COALESCE(w.deleted, 0) <> 0
                             OR COALESCE(p.deleted, 0) <> 0
                             OR COALESCE(v.deleted, 0) <> 0
                        THEN 1 ELSE 0 END,
                   #{syncedAt}, #{syncedAt}
              FROM rigour_erp.erp_stock_balance b
              LEFT JOIN rigour_erp.erp_inventory_warehouse w
                ON w.tenant_id = b.tenant_id AND w.id = b.warehouse_id
              LEFT JOIN rigour_erp.erp_product p
                ON p.tenant_id = b.tenant_id AND p.id = b.product_id
              LEFT JOIN rigour_erp.erp_product_variant v
                ON v.tenant_id = b.tenant_id AND v.id = b.product_variant_id
             WHERE b.tenant_id = #{tenantId}
            ON DUPLICATE KEY UPDATE
                warehouse_code = VALUES(warehouse_code),
                warehouse_name = VALUES(warehouse_name),
                region_code = VALUES(region_code),
                product_code = VALUES(product_code),
                product_name = VALUES(product_name),
                product_category_id = VALUES(product_category_id),
                variant_code = VALUES(variant_code),
                unit_code = VALUES(unit_code),
                specification_snapshot = VALUES(specification_snapshot),
                available_quantity = VALUES(available_quantity),
                locked_quantity = VALUES(locked_quantity),
                in_transit_quantity = VALUES(in_transit_quantity),
                source_updated_time = VALUES(source_updated_time),
                synced_time = VALUES(synced_time),
                deleted = VALUES(deleted),
                updated_time = VALUES(updated_time)
            """)
    int upsertInventoryBalanceCurrentFromSource(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Select("""
            SELECT COALESCE(SUM(rowCount), 0) AS rowCount,
                   MAX(watermarkTime) AS watermarkTime
              FROM (
                    SELECT COUNT(*) AS rowCount,
                           MAX(GREATEST(po.updated_time, l.updated_time,
                                        COALESCE(p.updated_time, l.updated_time),
                                        COALESCE(pc.updated_time, l.updated_time),
                                        COALESCE(v.updated_time, l.updated_time))) AS watermarkTime
                      FROM rigour_erp.erp_procurement_order_line l
                      INNER JOIN rigour_erp.erp_procurement_order po
                        ON po.tenant_id = l.tenant_id AND po.id = l.procurement_order_id
                      LEFT JOIN rigour_erp.erp_product p
                        ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                      LEFT JOIN rigour_erp.erp_product_category pc
                        ON pc.tenant_id = p.tenant_id AND pc.id = p.category_id
                      LEFT JOIN rigour_erp.erp_product_variant v
                        ON v.tenant_id = l.tenant_id AND v.id = l.product_variant_id
                     WHERE l.tenant_id = #{tenantId}
                       AND l.deleted = 0
                       AND po.deleted = 0
                    UNION ALL
                    SELECT COUNT(*) AS rowCount,
                           MAX(GREATEST(so.updated_time, l.updated_time,
                                        COALESCE(p.updated_time, l.updated_time),
                                        COALESCE(pc.updated_time, l.updated_time),
                                        COALESCE(v.updated_time, l.updated_time))) AS watermarkTime
                      FROM rigour_erp.erp_stock_out_order_line l
                      INNER JOIN rigour_erp.erp_stock_out_order so
                        ON so.tenant_id = l.tenant_id AND so.id = l.stock_out_order_id
                      LEFT JOIN rigour_erp.erp_product p
                        ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                      LEFT JOIN rigour_erp.erp_product_category pc
                        ON pc.tenant_id = p.tenant_id AND pc.id = p.category_id
                      LEFT JOIN rigour_erp.erp_product_variant v
                        ON v.tenant_id = l.tenant_id AND v.id = l.product_variant_id
                     WHERE l.tenant_id = #{tenantId}
                       AND l.deleted = 0
                       AND so.deleted = 0
                       AND so.stock_out_type_code = 'SALES'
              ) summary
            """)
    Map<String, Object> inventoryOperationSourceSummary(@Param("tenantId") String tenantId);

    @Update("""
            UPDATE bi_inventory_operation_fact
               SET deleted = 1,
                   synced_time = #{syncedAt},
                   updated_time = #{syncedAt}
             WHERE tenant_id = #{tenantId}
            """)
    int markInventoryOperationFactDeleted(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Insert("""
            INSERT INTO bi_inventory_operation_fact (
                tenant_id, operation_type, source_document_id, source_line_id, source_document_no,
                operation_time, product_id, product_code, product_name,
                product_category_id, product_category_code, product_category_name,
                product_variant_id, variant_code, unit_code, quantity,
                source_updated_time, synced_time, deleted, created_time, updated_time
            )
            SELECT l.tenant_id, 'PROCUREMENT', po.id, l.id, po.procurement_no,
                   COALESCE(po.expected_arrival_time, po.created_time),
                   l.product_id, COALESCE(l.product_code_snapshot, p.product_code),
                   COALESCE(l.product_name_snapshot, p.product_name),
                   p.category_id, pc.category_code, pc.category_name,
                   l.product_variant_id, COALESCE(l.variant_code_snapshot, v.variant_code),
                   COALESCE(l.unit_code, v.unit_code, p.unit_code, 'UNKNOWN'),
                   l.quantity,
                   GREATEST(po.updated_time, l.updated_time,
                            COALESCE(p.updated_time, l.updated_time),
                            COALESCE(pc.updated_time, l.updated_time),
                            COALESCE(v.updated_time, l.updated_time)),
                   #{syncedAt}, 0, #{syncedAt}, #{syncedAt}
              FROM rigour_erp.erp_procurement_order_line l
              INNER JOIN rigour_erp.erp_procurement_order po
                ON po.tenant_id = l.tenant_id AND po.id = l.procurement_order_id
              LEFT JOIN rigour_erp.erp_product p
                ON p.tenant_id = l.tenant_id AND p.id = l.product_id
              LEFT JOIN rigour_erp.erp_product_category pc
                ON pc.tenant_id = p.tenant_id AND pc.id = p.category_id
              LEFT JOIN rigour_erp.erp_product_variant v
                ON v.tenant_id = l.tenant_id AND v.id = l.product_variant_id
             WHERE l.tenant_id = #{tenantId}
               AND l.deleted = 0
               AND po.deleted = 0
               AND po.status_code NOT IN ('DRAFT', 'CANCELLED')
            ON DUPLICATE KEY UPDATE
                source_document_no = VALUES(source_document_no),
                operation_time = VALUES(operation_time),
                product_id = VALUES(product_id),
                product_code = VALUES(product_code),
                product_name = VALUES(product_name),
                product_category_id = VALUES(product_category_id),
                product_category_code = VALUES(product_category_code),
                product_category_name = VALUES(product_category_name),
                product_variant_id = VALUES(product_variant_id),
                variant_code = VALUES(variant_code),
                unit_code = VALUES(unit_code),
                quantity = VALUES(quantity),
                source_updated_time = VALUES(source_updated_time),
                synced_time = VALUES(synced_time),
                deleted = 0,
                updated_time = VALUES(updated_time)
            """)
    int upsertProcurementOperationFactFromSource(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Insert("""
            INSERT INTO bi_inventory_operation_fact (
                tenant_id, operation_type, source_document_id, source_line_id, source_document_no,
                operation_time, product_id, product_code, product_name,
                product_category_id, product_category_code, product_category_name,
                product_variant_id, variant_code, unit_code, quantity,
                source_updated_time, synced_time, deleted, created_time, updated_time
            )
            SELECT l.tenant_id, 'SALES_SHIPMENT', so.id, l.id, so.stock_out_no,
                   COALESCE(so.stock_out_time, so.created_time),
                   l.product_id, COALESCE(l.product_code_snapshot, p.product_code),
                   COALESCE(l.product_name_snapshot, p.product_name),
                   p.category_id, pc.category_code, pc.category_name,
                   l.product_variant_id, COALESCE(l.variant_code_snapshot, v.variant_code),
                   COALESCE(l.unit_code, v.unit_code, p.unit_code, 'UNKNOWN'),
                   l.quantity,
                   GREATEST(so.updated_time, l.updated_time,
                            COALESCE(p.updated_time, l.updated_time),
                            COALESCE(pc.updated_time, l.updated_time),
                            COALESCE(v.updated_time, l.updated_time)),
                   #{syncedAt}, 0, #{syncedAt}, #{syncedAt}
              FROM rigour_erp.erp_stock_out_order_line l
              INNER JOIN rigour_erp.erp_stock_out_order so
                ON so.tenant_id = l.tenant_id AND so.id = l.stock_out_order_id
              LEFT JOIN rigour_erp.erp_product p
                ON p.tenant_id = l.tenant_id AND p.id = l.product_id
              LEFT JOIN rigour_erp.erp_product_category pc
                ON pc.tenant_id = p.tenant_id AND pc.id = p.category_id
              LEFT JOIN rigour_erp.erp_product_variant v
                ON v.tenant_id = l.tenant_id AND v.id = l.product_variant_id
             WHERE l.tenant_id = #{tenantId}
               AND l.deleted = 0
               AND so.deleted = 0
               AND so.stock_out_type_code = 'SALES'
               AND so.status_code NOT IN ('DRAFT', 'CANCELLED')
            ON DUPLICATE KEY UPDATE
                source_document_no = VALUES(source_document_no),
                operation_time = VALUES(operation_time),
                product_id = VALUES(product_id),
                product_code = VALUES(product_code),
                product_name = VALUES(product_name),
                product_category_id = VALUES(product_category_id),
                product_category_code = VALUES(product_category_code),
                product_category_name = VALUES(product_category_name),
                product_variant_id = VALUES(product_variant_id),
                variant_code = VALUES(variant_code),
                unit_code = VALUES(unit_code),
                quantity = VALUES(quantity),
                source_updated_time = VALUES(source_updated_time),
                synced_time = VALUES(synced_time),
                deleted = 0,
                updated_time = VALUES(updated_time)
            """)
    int upsertSalesShipmentOperationFactFromSource(
            @Param("tenantId") String tenantId,
            @Param("syncedAt") LocalDateTime syncedAt);

    @Insert("""
            INSERT INTO bi_city_cost_record (
                tenant_id, region_code, region_name, cost_type_code, cost_type_name, cost_date,
                cost_amount, budget_amount, source_system_code, source_record_id, remark,
                revision, created_by, created_time, updated_by, updated_time, deleted
            ) VALUES (
                #{tenantId}, #{regionCode}, #{regionName}, #{costTypeCode}, #{costTypeName}, #{costDate},
                #{costAmount}, #{budgetAmount}, #{sourceSystemCode}, #{sourceRecordId}, #{remark},
                1, 'BI_IMPORT', #{importedAt}, 'BI_IMPORT', #{importedAt}, 0
            )
            ON DUPLICATE KEY UPDATE
                region_code = VALUES(region_code),
                region_name = VALUES(region_name),
                cost_type_code = VALUES(cost_type_code),
                cost_type_name = VALUES(cost_type_name),
                cost_date = VALUES(cost_date),
                cost_amount = VALUES(cost_amount),
                budget_amount = VALUES(budget_amount),
                remark = VALUES(remark),
                revision = revision + 1,
                updated_by = 'BI_IMPORT',
                updated_time = VALUES(updated_time),
                deleted = 0
            """)
    int upsertCityCostRecord(
            @Param("tenantId") String tenantId,
            @Param("sourceSystemCode") String sourceSystemCode,
            @Param("sourceRecordId") String sourceRecordId,
            @Param("regionCode") String regionCode,
            @Param("regionName") String regionName,
            @Param("costTypeCode") String costTypeCode,
            @Param("costTypeName") String costTypeName,
            @Param("costDate") LocalDateTime costDate,
            @Param("costAmount") BigDecimal costAmount,
            @Param("budgetAmount") BigDecimal budgetAmount,
            @Param("remark") String remark,
            @Param("importedAt") LocalDateTime importedAt);

    @Insert("""
            INSERT INTO bi_feishu_legacy_archive (
                tenant_id, archive_code, table_id, view_id, table_name, file_name, file_format,
                exported_by, exported_time, frozen_time, record_count, checksum_sha256,
                storage_uri, field_mapping_uri, reconciliation_report_uri, archive_status_code,
                remark, created_time, updated_time, deleted
            ) VALUES (
                #{tenantId}, #{archiveCode}, #{tableId}, #{viewId}, #{tableName}, #{fileName}, #{fileFormat},
                #{exportedBy}, #{exportedTime}, #{frozenTime}, #{recordCount}, #{checksumSha256},
                #{storageUri}, #{fieldMappingUri}, #{reconciliationReportUri}, 'ARCHIVED',
                #{remark}, #{registeredAt}, #{registeredAt}, 0
            )
            ON DUPLICATE KEY UPDATE
                archive_code = VALUES(archive_code),
                table_id = VALUES(table_id),
                view_id = VALUES(view_id),
                table_name = VALUES(table_name),
                file_name = VALUES(file_name),
                file_format = VALUES(file_format),
                exported_by = VALUES(exported_by),
                exported_time = VALUES(exported_time),
                frozen_time = VALUES(frozen_time),
                record_count = VALUES(record_count),
                checksum_sha256 = VALUES(checksum_sha256),
                storage_uri = VALUES(storage_uri),
                field_mapping_uri = VALUES(field_mapping_uri),
                reconciliation_report_uri = VALUES(reconciliation_report_uri),
                archive_status_code = 'ARCHIVED',
                remark = VALUES(remark),
                updated_time = VALUES(updated_time),
                deleted = 0
            """)
    int upsertFeishuArchive(
            @Param("tenantId") String tenantId,
            @Param("archiveCode") String archiveCode,
            @Param("tableId") String tableId,
            @Param("viewId") String viewId,
            @Param("tableName") String tableName,
            @Param("fileName") String fileName,
            @Param("fileFormat") String fileFormat,
            @Param("exportedBy") String exportedBy,
            @Param("exportedTime") LocalDateTime exportedTime,
            @Param("frozenTime") LocalDateTime frozenTime,
            @Param("recordCount") Long recordCount,
            @Param("checksumSha256") String checksumSha256,
            @Param("storageUri") String storageUri,
            @Param("fieldMappingUri") String fieldMappingUri,
            @Param("reconciliationReportUri") String reconciliationReportUri,
            @Param("remark") String remark,
            @Param("registeredAt") LocalDateTime registeredAt);

    @Select("""
            SELECT id, archive_code AS archiveCode, table_id AS tableId, view_id AS viewId,
                   table_name AS tableName, file_name AS fileName, file_format AS fileFormat,
                   exported_by AS exportedBy, exported_time AS exportedTime, frozen_time AS frozenTime,
                   record_count AS recordCount, checksum_sha256 AS checksumSha256,
                   storage_uri AS storageUri, field_mapping_uri AS fieldMappingUri,
                   reconciliation_report_uri AS reconciliationReportUri,
                   archive_status_code AS archiveStatusCode, remark,
                   created_time AS createdTime, updated_time AS updatedTime
              FROM bi_feishu_legacy_archive
             WHERE tenant_id = #{tenantId}
               AND archive_code = #{archiveCode}
               AND deleted = 0
             LIMIT 1
            """)
    Map<String, Object> feishuArchiveByCode(
            @Param("tenantId") String tenantId,
            @Param("archiveCode") String archiveCode);

    @Select("""
            SELECT id, archive_code AS archiveCode, table_id AS tableId, view_id AS viewId,
                   table_name AS tableName, file_name AS fileName, file_format AS fileFormat,
                   exported_by AS exportedBy, exported_time AS exportedTime, frozen_time AS frozenTime,
                   record_count AS recordCount, checksum_sha256 AS checksumSha256,
                   storage_uri AS storageUri, field_mapping_uri AS fieldMappingUri,
                   reconciliation_report_uri AS reconciliationReportUri,
                   archive_status_code AS archiveStatusCode, remark,
                   created_time AS createdTime, updated_time AS updatedTime
              FROM bi_feishu_legacy_archive
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
             ORDER BY exported_time DESC, id DESC
             LIMIT 200
            """)
    List<Map<String, Object>> feishuArchives(@Param("tenantId") String tenantId);
}
