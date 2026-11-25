package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.application.model.*;
import com.rigour.analytics.application.model.SalesDashboardData.*;
import com.rigour.analytics.application.port.out.SalesDashboardStore;
import com.rigour.analytics.infrastructure.persistence.scope.BiScopedQueries;

import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import static com.rigour.analytics.infrastructure.persistence.scope.OperatingCitySql.JDBC_REGION_SCOPE;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class JdbcSalesDashboardStore implements SalesDashboardStore {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rigour.shared.cache.QueryCache queryCache = com.rigour.shared.cache.QueryCache.disabled();

    private final NamedParameterJdbcTemplate jdbc;

    private final HrDashboardTargets targets;

    public JdbcSalesDashboardStore(javax.sql.DataSource dataSource, HrDashboardTargets targets) {
        this.targets=targets;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    // Orders retain cohort ownership; period cash uses the resolved payment performance owner.
    // Scoped fact CTEs still enforce the authorization boundary before either aggregation.
    static final String BASE =
            """
            WITH orders AS (
              SELECT o.* FROM bi_sales_order_fact o
               WHERE o.tenant_id=:tenant AND o.deleted=0 AND o.order_status_code NOT IN ('CANCELLED','DELETED')
                 AND (:region IS NULL OR o.region_code""" + JDBC_REGION_SCOPE + """
)
                 AND (:owner IS NULL OR o.owner_staff_code=:owner)
                 AND (:customerType IS NULL OR o.customer_type_code=:customerType)
                 AND (:source IS NULL OR o.source_system_code=:source)
            ), payments AS (
              SELECT p.*,o.order_date AS cohort_date FROM bi_sales_payment_fact p
              LEFT JOIN bi_sales_order_fact o ON o.tenant_id=p.tenant_id AND o.order_id=p.order_id
              WHERE p.tenant_id=:tenant AND p.deleted=0
                AND (:region IS NULL OR p.region_code""" + JDBC_REGION_SCOPE + """
)
                AND (:owner IS NULL OR p.owner_staff_code=:owner)
                AND (:customerType IS NULL OR p.customer_type_code=:customerType)
                AND (:source IS NULL OR p.source_system_code=:source)
            )
            """;

    @Override
    @Transactional(readOnly = true)
    public SalesDashboardSummary summary(String tenant, SupplyDashboardFilter f, List<String> owners) {
        return queryCache.read(tenant, "summary", java.util.Arrays.asList(f, owners.stream().sorted().toList()), 60,
                new tools.jackson.core.type.TypeReference<>() {}, () -> loadSummary(tenant, f, owners));
    }

    private SalesDashboardSummary loadSummary(String tenant, SupplyDashboardFilter f, List<String> owners) {
        var args = new MapSqlParameterSource("tenant", tenant)
                .addValue("from", Timestamp.from(f.from())).addValue("to", Timestamp.from(f.to()))
                .addValue("region", f.regionCode()).addValue("owner", f.ownerStaffCode())
                .addValue("customerType", f.customerTypeCode()).addValue("source", f.sourceSystemCode())
                .addValue("owners", owners);
        // The selection only narrows authorized facts; customer history is tenant-wide,
        // matching customerRetention even when a customer has changed salesperson or city.
        return BiScopedQueries.queryForObject(jdbc, BASE + """
            SELECT COALESCE(SUM(o.payable_amount),0) sales,
                   (SELECT COALESCE(SUM(p.paid_amount),0) FROM payments p
                     WHERE p.payment_time BETWEEN :from AND :to
                       AND p.owner_staff_code IN (:owners)) receipts,
                   COUNT(DISTINCT o.customer_id) customers,
                   COUNT(DISTINCT CASE WHEN h.first_order_date BETWEEN :from AND :to
                         THEN o.customer_id END) newCustomers,
                   COUNT(DISTINCT CASE WHEN h.first_order_date < :from
                         THEN o.customer_id END) returningCustomers,
                   COUNT(DISTINCT CASE WHEN
                       (YEAR(TIMESTAMPADD(HOUR,8,h.first_order_date))*12
                         + MONTH(TIMESTAMPADD(HOUR,8,h.first_order_date)))
                       < (YEAR(TIMESTAMPADD(HOUR,8,o.order_date))*12
                         + MONTH(TIMESTAMPADD(HOUR,8,o.order_date)))
                         THEN o.customer_id END) annualReturningCustomers
              FROM orders o
              LEFT JOIN bi_dashboard_customer_history h
                ON h.tenant_id=o.tenant_id AND h.customer_id=o.customer_id
             WHERE o.order_date BETWEEN :from AND :to AND o.owner_staff_code IN (:owners)
            """, args, (r, n) -> new SalesDashboardSummary(r.getBigDecimal("sales"),
                r.getBigDecimal("receipts"), r.getLong("customers"), r.getLong("newCustomers"),
                r.getLong("returningCustomers"), r.getLong("annualReturningCustomers")));
    }

    @Override
    @Transactional(readOnly = true)
    public SalesDashboardData query(String tenant, SupplyDashboardFilter f) {
        return query(tenant, f, Projection.FULL);
    }

    @Override
    @Transactional(readOnly = true)
    public SalesDashboardData query(String tenant, SupplyDashboardFilter f, Projection projection) {
        return queryCache.read(tenant, "salesDashboard", List.of(f, projection), 60,
                new tools.jackson.core.type.TypeReference<>() {}, () -> loadQuery(tenant, f, projection));
    }

    private record RankingRow(Person person, DailyReceipt daily) {}

    private SalesDashboardData loadQuery(String tenant, SupplyDashboardFilter f, Projection projection) {
        int year = f.from().atZone(BiBusinessTime.ZONE).getYear();
        var args =
                new MapSqlParameterSource("tenant", tenant)
                        .addValue("from", Timestamp.from(f.from()))
                        .addValue("to", Timestamp.from(f.to()))
                        .addValue("region", f.regionCode())
                        .addValue("owner", f.ownerStaffCode())
                        .addValue("customerType", f.customerTypeCode())
                        .addValue("source", f.sourceSystemCode())
                        .addValue("year", year)
                        .addValue(
                                "yearStart",
                                Timestamp.from(
                                        LocalDate.of(year, 1, 1)
                                                .atStartOfDay(BiBusinessTime.ZONE)
                                                .toInstant()))
                        .addValue(
                                "yearEnd",
                                Timestamp.from(
                                        LocalDate.of(year + 1, 1, 1)
                                                .atStartOfDay(BiBusinessTime.ZONE)
                                                .toInstant()));
        var ranking =
                BiScopedQueries.query(
                        jdbc,
                        BASE
                                + """
, sales AS (SELECT owner_staff_code code,MAX(owner_staff_name) name,
           GROUP_CONCAT(DISTINCT region_name ORDER BY region_name SEPARATOR '、') city,
           SUM(payable_amount) sales,SUM(paid_amount) paid
    FROM orders WHERE order_date BETWEEN :from AND :to GROUP BY owner_staff_code),
period_payments AS (SELECT owner_staff_code,owner_staff_name,region_name,payment_time,paid_amount
    FROM payments WHERE payment_time BETWEEN :from AND :to),
cash AS (SELECT owner_staff_code code,MAX(owner_staff_name) name,
           GROUP_CONCAT(DISTINCT region_name ORDER BY region_name SEPARATOR '、') city,SUM(paid_amount) receipts
    FROM period_payments GROUP BY owner_staff_code),
roster AS (SELECT DISTINCT e.employee_code code FROM bi_employee_dim e
    JOIN bi_sales_contact_city_dim c ON c.tenant_id=e.tenant_id
      AND (e.department_id=c.department_id OR REPLACE(REPLACE(REPLACE(e.department_path,'[',','),']',','),' ','') LIKE CONCAT('%,',c.department_id,',%'))
    WHERE e.tenant_id=:tenant AND (:region IS NULL OR c.region_code""" + JDBC_REGION_SCOPE + """
)
      AND (:owner IS NULL OR e.employee_code=:owner)),
codes AS (SELECT code FROM sales UNION SELECT code FROM cash UNION SELECT code FROM roster)
SELECT c.code,COALESCE(e.employee_name,s.name,p.name,c.code) name,
       COALESCE(s.city,p.city,e.city_name,'归属待核对') city,e.employment_status,
       COALESCE(s.sales,0) sales,COALESCE(s.paid,0) paid,COALESCE(p.receipts,0) receipts,NULL period
  FROM codes c LEFT JOIN sales s ON s.code=c.code LEFT JOIN cash p ON p.code=c.code
  LEFT JOIN bi_employee_dim e ON e.tenant_id=:tenant AND e.employee_code=c.code
 WHERE c.code IS NOT NULL AND TRIM(c.code)<>'' AND UPPER(c.code) NOT IN ('UNKNOWN','MULTI')
 UNION ALL
 SELECT NULL,NULL,NULL,NULL,0,0,SUM(paid_amount),DATE_FORMAT(TIMESTAMPADD(HOUR,8,payment_time),'%Y-%m-%d')
   FROM period_payments GROUP BY DATE_FORMAT(TIMESTAMPADD(HOUR,8,payment_time),'%Y-%m-%d')
 ORDER BY period,sales DESC,code
""",
                        args,
                        (r, n) -> {
                            String period = r.getString("period");
                            if (period != null)
                                return new RankingRow(null, new DailyReceipt(period, r.getBigDecimal("receipts")));
                            return new RankingRow(new Person(
                                        r.getString("code"),
                                        r.getString("name"),
                                        r.getString("city"),
                                        r.getString("employment_status"),
                                        r.getBigDecimal("sales"),
                                        r.getBigDecimal("paid"),
                                        r.getBigDecimal("receipts")), null);
                        });
        var people = ranking.stream().map(RankingRow::person).filter(java.util.Objects::nonNull).toList();
        var daily = ranking.stream().map(RankingRow::daily).filter(java.util.Objects::nonNull).toList();
        var codes = people.stream().map(Person::code).collect(Collectors.toSet());
        var goals = projection == Projection.COMPARISON || codes.isEmpty() ? List.<Goal>of()
            : targets.values(tenant,year+"-01",year+"-12").stream()
            .filter(g -> "SALES_OWNER".equals(g.dimensionType()) && codes.contains(g.code()))
            .map(g -> new Goal(g.code(),YearMonth.parse(g.month()).getMonthValue(),g.metric(),g.value())).toList();
        if (f.ownerStaffCode() == null || projection != Projection.FULL)
            return new SalesDashboardData(
                    people, goals, null, List.of(), List.of(), List.of(), null, null, daily);
        var history =
                BiScopedQueries.queryForObject(
                        jdbc,
                        BASE
                                + "SELECT COALESCE(SUM(payable_amount),0)"
                                + " amount,COALESCE(SUM(paid_amount),0) received FROM orders",
                        args,
                        (r, n) ->
                                new Cohort(r.getBigDecimal("amount"), r.getBigDecimal("received")));
        var customers =
                BiScopedQueries.query(
                        jdbc,
                        BASE
                                + """
SELECT CAST(customer_id AS CHAR) code,MAX(customer_name) name,SUM(payable_amount) sales,SUM(paid_amount) received
  FROM orders WHERE order_date BETWEEN :from AND :to GROUP BY customer_id
 ORDER BY sales DESC,code
""",
                        args,
                        (r, n) ->
                                new Customer(
                                        r.getString("code"),
                                        r.getString("name"),
                                        r.getBigDecimal("sales"),
                                        r.getBigDecimal("received")));
        var split =
                BiScopedQueries.queryForObject(
                        jdbc,
                        BASE
                                + """
SELECT COALESCE(SUM(CASE WHEN cohort_date BETWEEN :from AND :to THEN paid_amount ELSE 0 END),0) currentOrders,
       COALESCE(SUM(CASE WHEN cohort_date<:from THEN paid_amount ELSE 0 END),0) historicalOrders,
       COALESCE(SUM(CASE WHEN cohort_date>:to OR cohort_date IS NULL THEN paid_amount ELSE 0 END),0) otherOrders
  FROM payments WHERE payment_time BETWEEN :from AND :to
""",
                        args,
                        (r, n) ->
                                new ReceiptSplit(
                                        r.getBigDecimal("currentOrders"),
                                        r.getBigDecimal("historicalOrders"),
                                        r.getBigDecimal("otherOrders")));
        var months =
                BiScopedQueries.query(
                        jdbc,
                        BASE
                                + """
, monthly AS (
  SELECT DATE_FORMAT(TIMESTAMPADD(HOUR,8,order_date),'%Y-%m') periodMonth,SUM(payable_amount) sales,SUM(paid_amount) received,0 receipts
    FROM orders WHERE order_date>=:yearStart AND order_date<:yearEnd GROUP BY periodMonth
  UNION ALL
  SELECT DATE_FORMAT(TIMESTAMPADD(HOUR,8,payment_time),'%Y-%m'),0,0,SUM(paid_amount)
    FROM payments WHERE payment_time>=:yearStart AND payment_time<:yearEnd GROUP BY 1)
SELECT periodMonth,SUM(sales) sales,SUM(received) received,SUM(receipts) receipts FROM monthly GROUP BY periodMonth ORDER BY periodMonth
""",
                        args,
                        (r, n) ->
                                new SalesDashboardData.Month(
                                        r.getString("periodMonth"),
                                        r.getBigDecimal("sales"),
                                        r.getBigDecimal("received"),
                                        r.getBigDecimal("receipts")));
        var sync =
                jdbc.query(
                        "SELECT synced_time FROM bi_dashboard_product_snapshot WHERE"
                                + " tenant_id=:tenant",
                        args,
                        (r, n) -> r.getTimestamp(1).toInstant());
        var products =
                sync.isEmpty()
                        ? List.<Product>of()
                        : BiScopedQueries.query(
                                jdbc,
                                BASE
                                        + """
, product_amounts AS (
  SELECT a.product_category_id,a.product_category_name,a.product_id,a.product_name,
         COALESCE(NULLIF(a.specification_snapshot,''),a.sku_code,'未注明型号') sku,
         l.quantity,a.sales_amount sales,a.cohort_paid_amount received,0 receipts,a.allocation_status
    FROM bi_dashboard_order_product a JOIN orders o ON o.tenant_id=a.tenant_id AND o.order_id=a.order_id
    LEFT JOIN bi_sales_order_line_fact l ON l.tenant_id=a.tenant_id AND l.order_id=a.order_id
      AND l.order_line_id=a.order_line_id AND l.deleted=0
   WHERE o.order_date BETWEEN :from AND :to
  UNION ALL
  SELECT a.product_category_id,a.product_category_name,a.product_id,a.product_name,
         COALESCE(NULLIF(a.specification_snapshot,''),a.sku_code,'未注明型号'),0,0,0,p.allocated_amount,p.allocation_status
    FROM bi_dashboard_payment_product p
    JOIN payments receipt ON receipt.tenant_id=p.tenant_id AND receipt.payment_id=p.payment_id
    JOIN bi_dashboard_order_product a ON a.tenant_id=p.tenant_id AND a.order_id=p.order_id AND a.order_line_id=p.order_line_id
   WHERE p.payment_time BETWEEN :from AND :to)
SELECT COALESCE(CAST(product_category_id AS CHAR),'UNKNOWN') categoryId,
       COALESCE(MAX(product_category_name),'未分类') category,
       COALESCE(CAST(product_id AS CHAR),'UNKNOWN') productId,COALESCE(MAX(product_name),'无法分摊') product,sku,
       CASE WHEN COUNT(quantity)=COUNT(*) THEN SUM(quantity) ELSE NULL END quantity,
       SUM(sales) sales,SUM(received) received,SUM(receipts) receipts,
       MIN(CASE WHEN allocation_status='ALLOCATED' THEN 1 ELSE 0 END) allocated
  FROM product_amounts GROUP BY product_category_id,product_id,sku ORDER BY sales DESC,productId,sku
""",
                                args,
                                (r, n) ->
                                        new Product(
                                                r.getString("categoryId"),
                                                r.getString("category"),
                                                r.getString("productId"),
                                                r.getString("product"),
                                                r.getString("sku"),
                                                r.getBigDecimal("quantity"),
                                                r.getBigDecimal("sales"),
                                                r.getBigDecimal("received"),
                                                r.getBigDecimal("receipts"),
                                                r.getBoolean("allocated"),
                                                null));
        return new SalesDashboardData(
                people,
                goals,
                history,
                products,
                customers,
                months,
                split,
                sync.isEmpty() ? null : sync.get(0),
                daily);
    }
}
