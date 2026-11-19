package com.rigour.hr.infrastructure.persistence.repository;

import com.rigour.hr.api.v1.model.SalesPayrollBoardView.Employee;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Repository
public class SalesStatementPublications {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SalesStatementPublications(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Employee> published(String tenant, String code, String month) {
        return jdbc
                .query(
                        """
SELECT s.employee_view,(SELECT SUM(p.amount) FROM hr_payroll_payment p
     WHERE p.tenant_id=s.tenant_id AND p.payroll_id=s.payroll_id) registered
FROM hr_sales_statement s WHERE s.tenant_id=? AND s.employee_code=? AND s.payroll_month=?
ORDER BY s.published_at DESC,s.revision DESC LIMIT 1
""",
                        (r, n) ->
                                json.readValue(r.getString(1), Employee.class)
                                        .withPayment(r.getBigDecimal(2)),
                        tenant,
                        code,
                        month)
                .stream()
                .findFirst();
    }

    public void publish(
            String tenant, String id, String month, int revision, String actor, Employee view) {
        var current =
                jdbc.queryForList(
                        "SELECT revision,status_code FROM hr_payroll WHERE tenant_id=? AND id=? FOR"
                            + " UPDATE",
                        tenant,
                        id);
        if (current.isEmpty()
                || !Set.of("PAID", "CONFIRMED").contains(current.getFirst().get("status_code"))
                || ((Number) current.getFirst().get("revision")).intValue() != revision)
            throw new BusinessException(ErrorCode.CONFLICT, "工资状态或版本已变化，请刷新后核对", List.of());
        jdbc.update(
                """
INSERT INTO hr_sales_statement(tenant_id,payroll_id,revision,employee_code,payroll_month,employee_view,published_by)
VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE payroll_id=hr_sales_statement.payroll_id
""",
                tenant,
                id,
                revision,
                view.code(),
                month,
                json.writeValueAsString(view),
                actor);
    }
}
