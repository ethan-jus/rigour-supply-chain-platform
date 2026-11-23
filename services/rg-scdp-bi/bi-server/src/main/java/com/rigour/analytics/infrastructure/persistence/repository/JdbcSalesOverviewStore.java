package com.rigour.analytics.infrastructure.persistence.repository;

import static com.rigour.analytics.infrastructure.persistence.repository.JdbcReceivablesStore.*;

import com.rigour.analytics.application.model.SalesOverview.*;
import com.rigour.analytics.application.port.out.ReceivablesStore.Scope;
import com.rigour.analytics.application.port.out.SalesOverviewStore;
import com.rigour.analytics.infrastructure.persistence.scope.BiScopedQueries;
import com.rigour.analytics.infrastructure.persistence.scope.OperatingCitySql;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class JdbcSalesOverviewStore implements SalesOverviewStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final HrDashboardTargets targets;

    public JdbcSalesOverviewStore(javax.sql.DataSource source, HrDashboardTargets targets) {
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.targets = targets;
    }

    // Sales are attributed to the original order owner, including when a different person collected
    // cash.
    private static final String ROSTER =
            """
, roster AS (
    SELECT e.employee_code code,e.employee_name name,e.city_name city
    FROM bi_employee_dim e
    WHERE e.tenant_id=:tenant AND e.employment_status='ACTIVE'
      AND EXISTS (
        SELECT 1 FROM bi_sales_contact_city_dim c
        WHERE c.tenant_id=e.tenant_id AND
          (e.department_id=c.department_id OR JSON_CONTAINS(e.department_path,CAST(c.department_id AS JSON))))
      AND (:departmentId IS NULL OR e.department_id=:departmentId
        OR (:includeSubDepartments AND JSON_CONTAINS(e.department_path,CAST(:departmentId AS JSON))))
)
""";

    @Override
    public List<Owner> owners(
            Scope scope, LocalDate from, LocalDate asOf, boolean finance, boolean receipts) {
        String appliedRows =
                finance
                        ? """
    UNION ALL
SELECT o.owner_staff_code code,o.owner_staff_name name,o.region_name city,0 booked,0 received,a.amount applied
FROM applied a JOIN visible_orders o ON o.order_id=a.order_id
WHERE a.effective_at>=:from AND o.order_date<:until
"""
                        : "";
        // Match the BI employee receipt KPI: actual receipt date and confirmed payment owner.
        String receiptRows =
                receipts
                        ? """
    UNION ALL
SELECT p.owner_staff_code code,p.owner_staff_name name,NULL city,0 booked,p.paid_amount received,0 applied
FROM bi_sales_payment_fact p
JOIN bi_sales_order_fact o ON o.tenant_id=p.tenant_id AND o.order_id=p.order_id
WHERE p.tenant_id=:tenant AND p.deleted=0 AND o.deleted=0
  AND o.order_status_code NOT IN ('DRAFT','CANCELLED','DELETED')
  AND p.payment_time>=:from AND p.payment_time<:until
  AND (:region IS NULL OR p.region_code\
"""
                                + OperatingCitySql.JDBC_REGION_SCOPE
                                + """
  )
  AND (:departmentId IS NULL OR EXISTS (
    SELECT 1 FROM bi_source_order_order_payment_record r
    LEFT JOIN bi_source_hr_hr_department d
      ON d.tenant_id=r.tenant_id AND d.id=r.payment_owner_department_id
    WHERE r.tenant_id=p.tenant_id AND r.id=p.payment_id AND r.deleted=0
      AND (r.payment_owner_department_id=:departmentId
        OR (:includeSubDepartments AND JSON_CONTAINS(d.department_path,CAST(:departmentId AS JSON))))
  ))
"""
                        : "";
        return BiScopedQueries.query(
                jdbc,
                ORDERS
                        + (finance ? VALID_APPLICATIONS : "")
                        + ROSTER
                        + """
, amounts AS (
    SELECT o.owner_staff_code code,o.owner_staff_name name,o.region_name city,o.payable_amount booked,0 received,0 applied
    FROM visible_orders o WHERE o.order_date>=:from AND o.order_date<:until
"""
                        + appliedRows
                        + receiptRows
                        + """
    UNION ALL SELECT code,name,city,0,0,0 FROM roster
), attributed AS (
    SELECT CASE WHEN UPPER(COALESCE(TRIM(code),'')) IN ('','UNKNOWN','MULTI') THEN NULL ELSE code END code,
           name,city,booked,received,applied FROM amounts
)
SELECT a.code, COALESCE(MAX(e.employee_name),
            IF(a.code IS NULL,'归属待核',COALESCE(MAX(NULLIF(a.name,'')),a.code))) name,
       COALESCE(MAX(e.city_name),MAX(NULLIF(a.city,''))) city,
       SUM(a.booked) booked, IF(:receipts,SUM(a.received),NULL) received, IF(:finance,SUM(a.applied),NULL) applied
FROM attributed a LEFT JOIN bi_employee_dim e ON e.tenant_id=:tenant AND e.employee_code=a.code
GROUP BY a.code ORDER BY a.code IS NULL,booked DESC,a.code
""",
                args(scope, asOf)
                        .addValue("from", boundary(from))
                        .addValue("finance", finance)
                        .addValue("receipts", receipts),
                (r, n) ->
                        new Owner(
                                r.getString("code"),
                                r.getString("name"),
                                r.getString("city"),
                                r.getBigDecimal("booked"),
                                r.getBigDecimal("received"),
                                r.getBigDecimal("applied")));
    }

    @Override
    public boolean receiptsAvailable(String tenant) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS (SELECT 1 FROM bi_etl_checkpoint
 WHERE tenant_id=:tenant AND source_code='ORDER_PAYMENT_RECORD' AND last_success_time IS NOT NULL)
""",
                        new MapSqlParameterSource("tenant", tenant),
                        Boolean.class));
    }

    @Override
    public List<Department> departments(String tenant) {
        var scope = new Scope(tenant, null, null, null, false);
        return BiScopedQueries.query(
                jdbc,
                ORDERS
                        + """
SELECT d.id,d.parent_id,d.department_name
FROM bi_source_hr_hr_department d
WHERE d.tenant_id=:tenant AND d.deleted=0 AND d.status_code='ACTIVE'
  AND (EXISTS (
      SELECT 1 FROM bi_employee_dim e WHERE e.tenant_id=d.tenant_id
        AND (e.department_id=d.id OR JSON_CONTAINS(e.department_path,CAST(d.id AS JSON))))
    OR EXISTS (
      SELECT 1 FROM bi_order_authority a
      JOIN visible_orders o ON o.tenant_id=a.tenant_id AND o.order_id=a.order_id
      WHERE a.tenant_id=d.tenant_id
        AND (a.department_id=d.id OR JSON_CONTAINS(a.department_path,CAST(d.id AS JSON)))))
ORDER BY d.department_name,d.id
""",
                args(scope, LocalDate.of(2000, 1, 1)),
                (r, n) ->
                        new Department(
                                r.getLong("id"),
                                r.getObject("parent_id", Long.class),
                                r.getString("department_name"),
                                n));
    }

    @Override
    public List<Target> targets(String tenant, String fromMonth, String toMonth) {
        return targets.values(tenant, fromMonth, toMonth);
    }

    @Override
    public DetailPage details(
            Scope scope,
            LocalDate from,
            LocalDate asOf,
            DetailKind kind,
            boolean finance,
            int page,
            int size) {
        String sql =
                switch (kind) {
                    case PRODUCTS ->
                            """
SELECT l.order_line_id id,o.order_no,o.owner_staff_name employee,
       DATE(TIMESTAMPADD(HOUR,8,o.order_date)) event_day, o.order_date sequence_at,
       CONCAT_WS(' · ',COALESCE(NULLIF(l.product_name,''),NULLIF(l.product_code,''),'商品待核'),
           COALESCE(NULLIF(l.specification_snapshot,''),NULLIF(l.sku_code,''))) name,
       l.line_amount amount,NULL applied
FROM visible_orders o JOIN bi_sales_order_line_fact l
  ON l.tenant_id=o.tenant_id AND l.order_id=o.order_id AND l.deleted=0
WHERE o.order_date>=:from AND o.order_date<:until
""";
                    case APPLICATIONS ->
                            """
SELECT a.id,o.order_no,o.owner_staff_name employee,
       DATE(TIMESTAMPADD(HOUR,8,a.effective_at)) event_day,a.effective_at sequence_at,
       a.reference_no name,a.amount amount,NULL applied
FROM applied a JOIN visible_orders o ON o.order_id=a.order_id
WHERE a.effective_at>=:from AND o.order_date<:until
""";
                    case ORDERS -> orderDetails(finance);
                };
        if (kind == DetailKind.APPLICATIONS || kind == DetailKind.ORDERS && finance) {
            sql = ORDERS + VALID_APPLICATIONS + ", details AS (" + sql + ")";
        } else {
            sql = ORDERS + ", details AS (" + sql + ")";
        }
        var bind =
                args(scope, asOf)
                        .addValue("from", boundary(from))
                        .addValue("size", size)
                        .addValue("skip", (page - 1L) * size);
        long total =
                BiScopedQueries.queryForObject(
                        jdbc, sql + " SELECT COUNT(*) FROM details", bind, (r, n) -> r.getLong(1));
        var items =
                total == 0
                        ? List.<Detail>of()
                        : BiScopedQueries.query(
                                jdbc,
                                sql
                                        + " SELECT * FROM details ORDER BY sequence_at DESC,id DESC"
                                        + " LIMIT :size OFFSET :skip",
                                bind,
                                (r, n) ->
                                        new Detail(
                                                r.getString("id"),
                                                r.getString("order_no"),
                                                r.getObject("event_day", LocalDate.class),
                                                r.getString("name"),
                                                r.getString("employee"),
                                                r.getBigDecimal("amount"),
                                                r.getBigDecimal("applied")));
        return new DetailPage(items, total);
    }

    private static String orderDetails(boolean finance) {
        return """
        SELECT o.order_id id,o.order_no,o.owner_staff_name employee,
               DATE(TIMESTAMPADD(HOUR,8,o.order_date)) event_day,o.order_date sequence_at,
               o.customer_name name,
               IF(o.order_date>=:from,o.payable_amount,0) amount,
        """
                + (finance ? " COALESCE(a.amount,0) applied" : " NULL applied")
                + """
                 FROM visible_orders o
                """
                + (finance
                        ? """
 LEFT JOIN (SELECT order_id,SUM(amount) amount FROM applied WHERE effective_at>=:from
            GROUP BY order_id) a ON a.order_id=o.order_id
"""
                        : "")
                + """
                 WHERE o.order_date<:until AND (o.order_date>=:from
                """
                + (finance ? " OR a.order_id IS NOT NULL" : "")
                + ")";
    }
}
