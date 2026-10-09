package com.rigour.hr.infrastructure.persistence.repository;

import com.rigour.hr.api.v1.model.TargetSettingsModels.*;
import com.rigour.hr.application.port.out.TargetSettingsStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository
public class JdbcTargetSettingsStore implements TargetSettingsStore {
    private final JdbcTemplate jdbc;
    private final HrDataScope scope;

    public JdbcTargetSettingsStore(JdbcTemplate jdbc, HrDataScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    @Override
    public Set<String> permittedSubjects(String tenant, String action) {
        var keys = new HashSet<String>();
        var employees = scope.employeePredicate(action, "");
        var employeeArgs = new ArrayList<Object>(List.of(tenant));
        employeeArgs.addAll(employees.args());
        keys.addAll(
                jdbc.query(
                        "SELECT employee_code FROM hr_employee WHERE tenant_id=? AND deleted=0 AND "
                                + employees.sql(),
                        (r, n) -> "SALES_OWNER:" + r.getString(1),
                        employeeArgs.toArray()));
        var departments = scope.departmentPredicate(action);
        var departmentArgs = new ArrayList<Object>(List.of(tenant));
        departmentArgs.addAll(departments.args());
        keys.addAll(
                jdbc.query(
                        "SELECT department_code FROM hr_department WHERE tenant_id=? AND deleted=0"
                            + " AND status_code='ACTIVE' AND "
                                + departments.sql(),
                        (r, n) -> "CITY:" + r.getString(1),
                        departmentArgs.toArray()));
        return keys;
    }

    // The closure includes every level. No target rows are needed to discover a new department or
    // employee.
    private static final String SALES_DEPARTMENT =
            """
            SELECT d.id FROM hr_department d
            WHERE d.tenant_id=? AND d.deleted=0 AND d.status_code='ACTIVE'
              AND EXISTS(SELECT 1 FROM hr_department_closure c JOIN hr_department root
                  ON root.tenant_id=c.tenant_id AND root.id=c.ancestor_id
                WHERE c.tenant_id=d.tenant_id AND c.descendant_id=d.id
                  AND root.department_name='销售部' AND root.deleted=0 AND root.status_code='ACTIVE')
            """;

    @Override
    public List<Subject> subjects(String tenant) {
        var result =
                new ArrayList<>(
                        jdbc.query(
                                """
                                SELECT department_code,department_name FROM hr_department
                                 WHERE tenant_id=? AND id IN (
                                """
                                        + SALES_DEPARTMENT
                                        + """
 ) AND department_name<>'销售部' ORDER BY department_name,department_code
""",
                                (r, n) ->
                                        new Subject(
                                                "CITY",
                                                r.getString(1),
                                                r.getString(2),
                                                r.getString(1),
                                                r.getString(2),
                                                null,
                                                null,
                                                false),
                                tenant,
                                tenant));
        result.addAll(
                jdbc.query(
                        """
SELECT e.employee_code,e.employee_name,d.department_code,d.department_name,e.employment_status
  FROM hr_employee e JOIN hr_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id
 WHERE e.tenant_id=? AND e.deleted=0 AND e.department_id IN (
"""
                                + SALES_DEPARTMENT
                                + """
                                 ) ORDER BY d.department_name,e.employee_name,e.employee_code
                                """,
                        (r, n) ->
                                new Subject(
                                        "SALES_OWNER",
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4),
                                        r.getString(4),
                                        r.getString(5),
                                        false),
                        tenant,
                        tenant));
        return result;
    }

    @Override
    public List<Target> targets(String tenant, String from, String to) {
        return jdbc.query(
                """
SELECT target_month,dimension_type,dimension_code,dimension_name,metric_code,target_value,revision
  FROM hr_business_target WHERE tenant_id=? AND target_month BETWEEN ? AND ?
""",
                (r, n) ->
                        new Target(
                                r.getDate(1).toLocalDate().toString().substring(0, 7),
                                r.getString(2),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                r.getBigDecimal(6),
                                r.getInt(7)),
                tenant,
                YearMonth.parse(from).atDay(1),
                YearMonth.parse(to).atDay(1));
    }

    @Override
    public void save(
            String tenant,
            String actor,
            String month,
            Subject subject,
            Change c,
            String reason,
            Instant now) {
        var date = YearMonth.parse(month).atDay(1);
        var at = Timestamp.from(now);
        if (c.expectedRevision() == 0) {
            try {
                jdbc.update(
                        """
INSERT INTO hr_business_target(tenant_id,target_month,dimension_type,dimension_code,dimension_name,
  metric_code,target_value,remark,revision,created_by,updated_by,created_time,updated_time)
VALUES(?,?,?,?,?,?,?,?,1,?,?,?,?)
""",
                        tenant,
                        date,
                        c.dimensionType(),
                        c.code(),
                        subject.name(),
                        c.metric(),
                        c.value(),
                        reason,
                        actor,
                        actor,
                        at,
                        at);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "目标已被其他人修改，请刷新后重试", List.of());
            }
        } else {
            int changed =
                    jdbc.update(
                            """
UPDATE hr_business_target SET target_value=?,dimension_name=?,remark=?,revision=revision+1,
    updated_by=?,updated_time=?
 WHERE tenant_id=? AND target_month=? AND dimension_type=? AND dimension_code=? AND metric_code=? AND revision=?
""",
                            c.value(),
                            subject.name(),
                            reason,
                            actor,
                            at,
                            tenant,
                            date,
                            c.dimensionType(),
                            c.code(),
                            c.metric(),
                            c.expectedRevision());
            if (changed != 1)
                throw new BusinessException(ErrorCode.CONFLICT, "目标已被其他人修改，请刷新后重试", List.of());
        }
        jdbc.update(
                """
INSERT INTO hr_business_target_event(id,tenant_id,target_id,revision,target_value,dimension_name,remark,actor,occurred_at)
SELECT ?,tenant_id,id,revision,target_value,dimension_name,remark,?,?
  FROM hr_business_target WHERE tenant_id=? AND target_month=? AND dimension_type=? AND dimension_code=? AND metric_code=?
""",
                UUID.randomUUID().toString(),
                actor,
                at,
                tenant,
                date,
                c.dimensionType(),
                c.code(),
                c.metric());
    }

    @Override
    public List<History> history(String tenant, String month, String type, String code) {
        return jdbc.query(
                """
SELECT t.metric_code,e.target_value,e.revision,e.remark,e.actor,e.occurred_at
  FROM hr_business_target_event e JOIN hr_business_target t ON t.tenant_id=e.tenant_id AND t.id=e.target_id
 WHERE t.tenant_id=? AND t.target_month=? AND t.dimension_type=? AND t.dimension_code=?
 ORDER BY e.occurred_at DESC,e.revision DESC
""",
                (r, n) ->
                        new History(
                                r.getString(1),
                                r.getBigDecimal(2),
                                r.getInt(3),
                                r.getString(4),
                                r.getString(5),
                                r.getTimestamp(6).toInstant()),
                tenant,
                YearMonth.parse(month).atDay(1),
                type,
                code);
    }
}
