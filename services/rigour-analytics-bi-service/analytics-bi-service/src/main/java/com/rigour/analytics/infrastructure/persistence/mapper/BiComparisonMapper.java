package com.rigour.analytics.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只读取 BI 订单事实的整单汇总；客户全量去重独立于城市分组。 */
public interface BiComparisonMapper {
    @Select("""
            <script>
            SELECT CASE WHEN order_date &gt;= #{boundary} THEN 'CURRENT' ELSE 'PREVIOUS' END AS period,
                   COALESCE(SUM(payable_amount), 0) AS salesAmount,
                   COALESCE(SUM(paid_amount), 0) AS paidAmount,
                   COALESCE(SUM(unpaid_amount), 0) AS unpaidAmount,
                   COUNT(*) AS orderCount, COUNT(DISTINCT customer_id) AS customerCount
            <if test="byCity">
                 , COALESCE(NULLIF(TRIM(region_code), ''), 'UNKNOWN') AS regionCode,
                   COALESCE(MAX(NULLIF(TRIM(region_name), '')), '未归属城市') AS regionName
            </if>
              FROM bi_sales_order_fact
             WHERE tenant_id = #{tenantId} AND deleted = 0
               AND order_status_code &lt;&gt; 'CANCELLED'
               AND order_date &gt;= #{from} AND order_date &lt;= #{to}
            <if test="region != null">AND region_code = #{region}</if>
            <if test="owner != null">AND owner_staff_code = #{owner}</if>
            <if test="customerType != null">AND customer_type_code = #{customerType}</if>
            <if test="source != null">AND source_system_code = #{source}</if>
             GROUP BY period
            <if test="byCity">, COALESCE(NULLIF(TRIM(region_code), ''), 'UNKNOWN')</if>
            </script>
            """)
    List<Map<String, Object>> aggregate(@Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("boundary") LocalDateTime boundary, @Param("region") String region,
            @Param("owner") String owner, @Param("customerType") String customerType,
            @Param("source") String source, @Param("byCity") boolean byCity);
}
