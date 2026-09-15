package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView.Employee;
import com.rigour.analytics.application.port.out.EmployeeAnalyticsStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SourceRefreshResult;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** HR 快照替换与 BI 本地员工分析；人员、客户、订单先分别聚合，防止多对多放大金额。 */
@Repository
public class JdbcEmployeeAnalyticsStore implements EmployeeAnalyticsStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEmployeeAnalyticsStore(DataSource dataSource) { this.jdbc = new NamedParameterJdbcTemplate(dataSource); }

    @Override
    public List<String> tenantIds() {
        return jdbc.query("SELECT DISTINCT tenant_id FROM rigour_hr.hr_employee", new MapSqlParameterSource(),
                (rs, index) -> rs.getString("tenant_id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refresh(String tenantId, Instant syncedAt) {
        var args = new MapSqlParameterSource("tenant", tenantId).addValue("synced", local(syncedAt));
        // 一个事务替换完整快照；来源失败会回滚旧投影，绝不把失败转换为零员工。
        jdbc.update("DELETE FROM bi_employee_dim WHERE tenant_id = :tenant", args);
        int count = jdbc.update("""
                INSERT INTO bi_employee_dim (tenant_id, employee_code, employee_name, employment_status,
                    city_name, region_code, position_name, department_name, entry_date, leave_date, synced_time)
                SELECT e.tenant_id, e.employee_code, e.employee_name, e.employment_status,
                       NULLIF(TRIM(e.city_name), ''), a.area_code,
                       COALESCE(NULLIF(p.position_name, ''), NULLIF(e.primary_position_name_snapshot, ''), NULLIF(e.job_category, '')),
                       NULLIF(TRIM(e.department_name_snapshot), ''), e.entry_date, e.leave_date, :synced
                  FROM rigour_hr.hr_employee e
                  LEFT JOIN rigour_hr.hr_position p ON p.tenant_id = e.tenant_id
                       AND p.position_code = e.primary_position_code AND p.deleted = 0
                  LEFT JOIN (
                       SELECT TRIM(area_name) AS area_name, MIN(area_code) AS area_code
                         FROM rigour_crm.crm_customer_area
                        WHERE tenant_id = UUID_TO_BIN(:tenant) AND deleted = 0 AND status = 'ACTIVE'
                        GROUP BY TRIM(area_name) HAVING COUNT(*) = 1
                  ) a ON a.area_name = TRIM(e.city_name)
                 WHERE e.tenant_id = :tenant AND e.deleted = 0
                """, args);
        jdbc.update("DELETE FROM bi_employee_snapshot WHERE tenant_id = :tenant", args);
        jdbc.update("INSERT INTO bi_employee_snapshot (tenant_id, synced_time) VALUES (:tenant, :synced)", args);
        return new SourceRefreshResult("HR_EMPLOYEE", "HR员工与岗位", (long) count, (long) count, 0L, syncedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot read(String tenantId, Instant from, Instant to, String regionCode, String employeeCode) {
        var args = new MapSqlParameterSource("tenant", tenantId).addValue("from", local(from)).addValue("to", local(to))
                .addValue("region", regionCode).addValue("employee", employeeCode);
        var times = jdbc.query("SELECT synced_time FROM bi_employee_snapshot WHERE tenant_id = :tenant", args,
                (rs, index) -> instant(rs, "synced_time"));
        if (times.isEmpty()) return new Snapshot(null, List.of());
        var readySources = jdbc.query("""
                SELECT source_code FROM bi_etl_checkpoint WHERE tenant_id = :tenant
                   AND source_code IN ('CRM_CUSTOMER', 'ORDER_SALES_ORDER') AND last_success_time IS NOT NULL
                """, args, (rs, index) -> rs.getString("source_code"));
        boolean customersReady = readySources.contains("CRM_CUSTOMER");
        boolean ordersReady = readySources.contains("ORDER_SALES_ORDER");

        var rows = jdbc.query("""
                SELECT e.*, COALESCE(c.customer_count, 0) AS customer_count,
                       COALESCE(o.order_count, 0) AS order_count,
                       COALESCE(o.sales_amount, 0) AS sales_amount, COALESCE(o.paid_amount, 0) AS paid_amount
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
                              SUM(payable_amount) AS sales_amount, SUM(paid_amount) AS paid_amount
                         FROM bi_sales_order_fact
                        WHERE tenant_id = :tenant AND deleted = 0
                          AND (order_status_code IS NULL OR order_status_code != 'CANCELLED')
                          AND order_date >= :from AND order_date <= :to
                          AND (:region IS NULL OR region_code = :region)
                          AND (:employee IS NULL OR owner_staff_code = :employee)
                        GROUP BY owner_staff_code
                  ) o ON o.owner_staff_code = e.employee_code
                 WHERE e.tenant_id = :tenant AND (:region IS NULL OR e.region_code = :region)
                   AND (:employee IS NULL OR e.employee_code = :employee)
                 ORDER BY sales_amount DESC, e.employee_code
                 LIMIT 20001
                """, args, (rs, index) -> new Row(new Employee(
                        rs.getString("employee_code"), rs.getString("employee_name"), rs.getString("employment_status"),
                        rs.getString("city_name"), rs.getString("position_name"), rs.getString("department_name"),
                        instant(rs, "entry_date"), instant(rs, "leave_date"), customersReady ? rs.getLong("customer_count") : null, ordersReady ? rs.getLong("order_count") : null,
                        ordersReady ? rs.getBigDecimal("sales_amount") : null, ordersReady ? rs.getBigDecimal("paid_amount") : null), rs.getString("region_code")));
        if (rows.size() > 20000) throw new BusinessException(ErrorCode.BAD_REQUEST, "员工统计超过20000人，请缩小城市或员工范围", List.of());
        return new Snapshot(times.getFirst(), rows);
    }

    private static LocalDateTime local(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
