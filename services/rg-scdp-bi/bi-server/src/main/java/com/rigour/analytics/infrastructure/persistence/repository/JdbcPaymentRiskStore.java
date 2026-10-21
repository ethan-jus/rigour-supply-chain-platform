package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.PaymentRiskView.Category;
import com.rigour.analytics.application.port.out.PaymentRiskStore;
import com.rigour.analytics.infrastructure.persistence.scope.BiScopedQueries;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentRiskStore implements PaymentRiskStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPaymentRiskStore(DataSource source) {
        jdbc = new NamedParameterJdbcTemplate(source);
    }

    @Override
    public List<Category> categories(String tenant) {
        return BiScopedQueries.query(jdbc, """
                SELECT category_id,parent_id,category_name FROM bi_product_category_dim
                 WHERE tenant_id=:tenant AND deleted=0 ORDER BY ordinal,category_id
                """, new MapSqlParameterSource("tenant", tenant),
                (r, n) -> new Category(r.getString(1), r.getString(2), r.getString(3)));
    }

    @Override
    public List<Fact> load(String tenant, Instant from, Instant to, String region, String owner, int limit) {
        var args = new MapSqlParameterSource("tenant", tenant)
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("region", region).addValue("owner", owner).addValue("limit", limit);
        // 完整订单数据权限先于商品关联；部分回款不抵扣风险金额，结清订单退出统计。
        return BiScopedQueries.query(jdbc, """
                SELECT o.order_id,o.order_no,o.customer_name,o.region_code,o.region_name,
                       o.owner_staff_code,o.owner_staff_name,o.order_date,
                       CASE WHEN p.product_id IS NOT NULL THEN p.product_category_id ELSE l.product_category_id END category_id,
                       CASE WHEN p.product_id IS NOT NULL THEN p.product_category_name ELSE l.product_category_name END category_name,
                       l.product_id,l.product_name,COALESCE(l.line_amount,0) line_amount,o.payable_amount,o.paid_amount,o.synced_time
                  FROM bi_sales_order_fact o
                  LEFT JOIN bi_sales_order_line_fact l
                    ON l.tenant_id=o.tenant_id AND l.order_id=o.order_id AND l.deleted=0
                  LEFT JOIN bi_product_dim p ON p.tenant_id=l.tenant_id AND p.product_id=l.product_id
                 WHERE o.tenant_id=:tenant AND o.deleted=0 AND COALESCE(o.order_status_code,'')<>'CANCELLED'
                   AND o.payable_amount>0 AND o.paid_amount<o.payable_amount
                   AND o.order_date BETWEEN :from AND :to
                   AND (:region IS NULL OR o.region_code=:region)
                   AND (:owner IS NULL OR o.owner_staff_code=:owner)
                 ORDER BY o.order_date,o.order_id,l.order_line_id LIMIT :limit
                """, args, (r, n) -> new Fact(
                r.getString("order_id"), r.getString("order_no"), r.getString("customer_name"),
                r.getString("region_code"), r.getString("region_name"),
                r.getString("owner_staff_code"), r.getString("owner_staff_name"),
                r.getTimestamp("order_date").toInstant(), r.getString("category_id"),
                r.getString("category_name"), r.getString("product_id"), r.getString("product_name"),
                r.getBigDecimal("payable_amount"), r.getBigDecimal("paid_amount"),
                r.getTimestamp("synced_time").toInstant(), r.getBigDecimal("line_amount")));
    }
}
