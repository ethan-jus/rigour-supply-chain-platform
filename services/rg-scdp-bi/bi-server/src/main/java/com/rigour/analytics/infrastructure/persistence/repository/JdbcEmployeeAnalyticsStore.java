package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView.Employee;
import com.rigour.analytics.application.port.out.EmployeeAnalyticsStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SourceRefreshResult;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

/** HR 快照替换与 BI 本地员工分析；人员、客户、订单先分别聚合，防止多对多放大金额。 */
@Repository
public class JdbcEmployeeAnalyticsStore implements EmployeeAnalyticsStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEmployeeAnalyticsStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<String> tenantIds() {
        return jdbc.query(
                "SELECT DISTINCT tenant_id FROM bi_source_hr_hr_employee",
                new MapSqlParameterSource(),
                (rs, index) -> rs.getString("tenant_id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refresh(String tenantId, Instant syncedAt) {
        var args =
                new MapSqlParameterSource("tenant", tenantId).addValue("synced", local(syncedAt));
        // 一个事务替换完整快照；来源失败会回滚旧投影，绝不把失败转换为零员工。
        // HR仅有城市名称。使用与筛选项相同的经营城市代码，精确名优先，简称唯一才关联；
        // CRM镜像没有地区层级，不能将省、散客或同名地区推断成经营城市。
        jdbc.update("DELETE FROM bi_employee_dim WHERE tenant_id = :tenant", args);
        int count =
                jdbc.update(
                        """
INSERT INTO bi_employee_dim (tenant_id, employee_code, employee_name, employment_status,
    city_name, region_code, position_name, department_name, department_id, department_path, entry_date, leave_date, synced_time)
WITH operating_cities(tenant_id,region_code,city_name,department_id,department_path,source_region_code) AS (
""" + com.rigour.analytics.infrastructure.persistence.scope.OperatingCitySql.SELECT.replace("#{tenantId}", ":tenant") + """
), eligible_cities AS (
    SELECT TRIM(city_name) AS city_name, region_code
      FROM operating_cities
     WHERE NULLIF(TRIM(city_name), '') IS NOT NULL
       AND TRIM(city_name) NOT IN ('总部','全国','散客','大客户地区')
       AND TRIM(city_name) NOT LIKE '%省' AND TRIM(city_name) NOT LIKE '%自治区'
), exact_names AS (
    SELECT city_name,MIN(region_code) AS region_code FROM eligible_cities
     GROUP BY city_name HAVING COUNT(DISTINCT region_code)=1
), city_aliases AS (
    SELECT CASE WHEN RIGHT(city_name,1)='市' THEN LEFT(city_name,CHAR_LENGTH(city_name)-1)
                ELSE city_name END AS city_alias, MIN(region_code) AS region_code,
           MIN(city_name) AS city_name
      FROM eligible_cities
     GROUP BY CASE WHEN RIGHT(city_name,1)='市' THEN LEFT(city_name,CHAR_LENGTH(city_name)-1)
                   ELSE city_name END
    HAVING COUNT(DISTINCT region_code)=1
)
SELECT e.tenant_id, e.employee_code, e.employee_name, e.employment_status,
       CASE WHEN TRIM(e.city_name)='总部' THEN '总部'
            ELSE COALESCE(exact_city.city_name,alias_city.city_name,NULLIF(TRIM(e.city_name),'')) END,
       CASE WHEN TRIM(e.city_name)='总部' THEN 'COST_HEADQUARTERS'
            ELSE COALESCE(exact_city.region_code,alias_city.region_code) END,
       e.position_name, e.department_name, e.department_id, e.department_path, e.entry_date,e.leave_date,:synced
  FROM bi_source_hr_hr_employee e
  LEFT JOIN exact_names exact_city ON exact_city.city_name=TRIM(e.city_name)
  LEFT JOIN city_aliases alias_city ON exact_city.region_code IS NULL
   AND alias_city.city_alias=CASE WHEN RIGHT(TRIM(e.city_name),1)='市'
           THEN LEFT(TRIM(e.city_name),CHAR_LENGTH(TRIM(e.city_name))-1) ELSE TRIM(e.city_name) END
 WHERE e.tenant_id = :tenant
""",
                        args);
        jdbc.update("DELETE FROM bi_employee_snapshot WHERE tenant_id = :tenant", args);
        jdbc.update(
                "INSERT INTO bi_employee_snapshot (tenant_id, synced_time) VALUES (:tenant,"
                    + " :synced)",
                args);
        return new SourceRefreshResult(
                "HR_EMPLOYEE", "HR员工与岗位", (long) count, (long) count, 0L, syncedAt);
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
                        "SELECT synced_time FROM bi_employee_snapshot WHERE tenant_id = :tenant",
                        args,
                        (rs, index) -> instant(rs, "synced_time"));
        if (times.isEmpty()) return new Snapshot(null, List.of());
        var readySources =
                jdbc.query(
                        """
SELECT source_code FROM bi_etl_checkpoint WHERE tenant_id = :tenant
   AND source_code IN ('CRM_CUSTOMER', 'ORDER_SALES_ORDER', 'ORDER_PAYMENT_RECORD') AND last_success_time IS NOT NULL
""",
                        args,
                        (rs, index) -> rs.getString("source_code"));
        boolean customersReady = readySources.contains("CRM_CUSTOMER");
        boolean ordersReady = readySources.contains("ORDER_SALES_ORDER");
        boolean receiptsReady = readySources.contains("ORDER_PAYMENT_RECORD");

        var rows =
                com.rigour.analytics.infrastructure.persistence.scope.BiScopedQueries.query(
                        jdbc,
                        """
SELECT e.*, COALESCE(c.customer_count, 0) AS customer_count,
       COALESCE(o.order_count, 0) AS order_count,
       COALESCE(o.sales_amount, 0) AS sales_amount, COALESCE(p.paid_amount, 0) AS paid_amount
  FROM bi_employee_dim e
  LEFT JOIN (
       SELECT owner_staff_code, COUNT(*) AS customer_count
         FROM bi_customer_dim
        WHERE tenant_id = :tenant AND deleted = 0
          AND (:region IS NULL OR region_code = :region)
          AND (:employee IS NULL OR owner_staff_code = :employee)
        GROUP BY owner_staff_code
  ) c ON c.owner_staff_code = e.employee_code
  LEFT JOIN (
       SELECT owner_staff_code, COUNT(*) AS order_count,
              SUM(payable_amount) AS sales_amount
         FROM bi_sales_order_fact
        WHERE tenant_id = :tenant AND deleted = 0
          AND (order_status_code IS NULL OR order_status_code != 'CANCELLED')
          AND order_date >= :from AND order_date <= :to
          AND (:region IS NULL OR region_code = :region)
          AND (:employee IS NULL OR owner_staff_code = :employee)
        GROUP BY owner_staff_code
  ) o ON o.owner_staff_code = e.employee_code
  LEFT JOIN (
       SELECT owner_staff_code, SUM(paid_amount) AS paid_amount
         FROM bi_sales_payment_fact
        WHERE tenant_id = :tenant AND deleted = 0
          AND payment_time >= :from AND payment_time <= :to
          AND (:region IS NULL OR region_code = :region)
          AND (:employee IS NULL OR owner_staff_code = :employee)
        GROUP BY owner_staff_code
  ) p ON p.owner_staff_code = e.employee_code
 WHERE e.tenant_id = :tenant AND (:region IS NULL OR e.region_code = :region)
   AND (:employee IS NULL OR e.employee_code = :employee)
 ORDER BY sales_amount DESC, e.employee_code
 LIMIT 20001
""",
                        args,
                        (rs, index) ->
                                new Row(
                                        new Employee(
                                                rs.getString("employee_code"),
                                                rs.getString("employee_name"),
                                                rs.getString("employment_status"),
                                                rs.getString("city_name"),
                                                rs.getString("position_name"),
                                                rs.getString("department_name"),
                                                instant(rs, "entry_date"),
                                                instant(rs, "leave_date"),
                                                customersReady
                                                        ? rs.getLong("customer_count")
                                                        : null,
                                                ordersReady ? rs.getLong("order_count") : null,
                                                ordersReady
                                                        ? rs.getBigDecimal("sales_amount")
                                                        : null,
                                                receiptsReady
                                                        ? rs.getBigDecimal("paid_amount")
                                                        : null),
                                        rs.getString("region_code")));
        if (rows.size() > 20000)
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST, "员工统计超过20000人，请缩小城市或员工范围", List.of());
        return new Snapshot(times.getFirst(), rows);
    }

    private static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
