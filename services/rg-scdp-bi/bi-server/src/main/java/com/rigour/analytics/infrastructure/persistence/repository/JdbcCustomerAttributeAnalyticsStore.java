package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.CustomerAttributeAnalyticsView.Item;
import com.rigour.analytics.application.port.out.CustomerAttributeAnalyticsStore;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

/** 仅查询 rigour_bi，客户和订单分别按权限范围收紧，当前属性不冒充成交时属性。 */
@Repository
public class JdbcCustomerAttributeAnalyticsStore implements CustomerAttributeAnalyticsStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCustomerAttributeAnalyticsStore(DataSource dataSource) {
        jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot read(
            String tenantId, Instant from, Instant to, String regionCode, String employeeCode) {
        var args =
                new MapSqlParameterSource("tenant", tenantId)
                        .addValue("from", local(from))
                        .addValue("to", local(to))
                        .addValue("region", regionCode)
                        .addValue("employee", employeeCode);
        var times =
                jdbc.query(
                        "SELECT synced_time FROM bi_customer_attribute_snapshot WHERE tenant_id ="
                            + " :tenant",
                        args,
                        (rs, i) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
        if (times.isEmpty()) return new Snapshot(null, List.of(), List.of());
        boolean ordersReady =
                !jdbc.query(
                                """
SELECT source_code FROM bi_etl_checkpoint WHERE tenant_id = :tenant
  AND source_code = 'ORDER_SALES_ORDER' AND last_success_time IS NOT NULL
""",
                                args,
                                (rs, i) -> rs.getString(1))
                        .isEmpty();
        return new Snapshot(
                times.getFirst(), items(args, false, ordersReady), items(args, true, ordersReady));
    }

    private List<Item> items(MapSqlParameterSource args, boolean category, boolean ordersReady) {
        // 标识符只从内部固定枚举选择，不拼接用户输入。
        String column = category ? "business_category_name" : "customer_source_name";
        return com.rigour.analytics.infrastructure.persistence.scope.BiScopedQueries.query(
                jdbc,
                """
SELECT a.%s AS attribute_name, COUNT(*) AS customer_count,
       SUM(CASE WHEN o.order_count > 0 THEN 1 ELSE 0 END) AS ordering_customer_count,
       COALESCE(SUM(o.order_count), 0) AS order_count,
       COALESCE(SUM(o.sales_amount), 0) AS sales_amount,
       COALESCE(SUM(o.paid_amount), 0) AS paid_amount
  FROM bi_customer_dim c
  INNER JOIN bi_customer_attribute_current a ON a.tenant_id = c.tenant_id AND a.customer_id = c.customer_id
  LEFT JOIN (
       SELECT customer_id, COUNT(*) AS order_count, SUM(payable_amount) AS sales_amount,
              SUM(paid_amount) AS paid_amount
         FROM bi_sales_order_fact
        WHERE tenant_id = :tenant AND deleted = 0
          AND (order_status_code IS NULL OR order_status_code <> 'CANCELLED')
          AND order_date >= :from AND order_date <= :to
          AND (:region IS NULL OR region_code = :region)
          AND (:employee IS NULL OR owner_staff_code = :employee)
        GROUP BY customer_id
  ) o ON o.customer_id = c.customer_id
 WHERE c.tenant_id = :tenant AND c.deleted = 0
   AND (:region IS NULL OR c.region_code = :region)
   AND (:employee IS NULL OR c.owner_staff_code = :employee)
 GROUP BY a.%s
 ORDER BY customer_count DESC, attribute_name
"""
                        .formatted(column, column),
                args,
                (rs, i) -> {
                    String name = rs.getString("attribute_name");
                    return new Item(
                            name == null ? "未填写" : name,
                            name == null,
                            rs.getLong("customer_count"),
                            ordersReady ? rs.getLong("ordering_customer_count") : null,
                            ordersReady ? rs.getLong("order_count") : null,
                            ordersReady ? rs.getBigDecimal("sales_amount") : null,
                            ordersReady ? rs.getBigDecimal("paid_amount") : null);
                });
    }

    private static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
