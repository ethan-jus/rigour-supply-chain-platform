package com.rigour.hr.infrastructure.persistence.repository;

import com.rigour.hr.application.port.out.HrHistoricalDepartmentStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/** HR 自己读取本租户的部门快照及任职历史，不向其他服务暴露 HR 业务表。 */
@Repository
public class JdbcHrHistoricalDepartmentStore implements HrHistoricalDepartmentStore {
    private final JdbcTemplate jdbc;

    public JdbcHrHistoricalDepartmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Employee> employees(String tenant, Set<String> codes) {
        if (codes.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT e.employee_code,e.employee_name,e.department_id,e.deleted,"
                    + " COALESCE(NULLIF(TRIM(e.department_name_snapshot),''),d.department_name)"
                    + " department_name FROM hr_employee e LEFT JOIN hr_department d ON"
                    + " d.tenant_id=e.tenant_id AND d.id=e.department_id WHERE e.tenant_id=? AND"
                    + " e.employee_code IN ("
                        + marks(codes)
                        + ")",
                (r, n) ->
                        new Employee(
                                r.getString("employee_code"),
                                r.getString("employee_name"),
                                r.getObject("department_id", Long.class),
                                r.getString("department_name"),
                                r.getInt("deleted") != 0),
                args(tenant, codes));
    }

    public List<Assignment> assignments(String tenant, Set<String> codes) {
        if (codes.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT"
                    + " employee_code,department_id,department_name_snapshot,effective_from,effective_to"
                    + " FROM hr_employee_assignment WHERE tenant_id=? AND employee_code IN ("
                        + marks(codes)
                        + ")"
                        + " ORDER BY employee_code,effective_from,id",
                (r, n) ->
                        new Assignment(
                                r.getString("employee_code"),
                                r.getLong("department_id"),
                                r.getString("department_name_snapshot"),
                                r.getTimestamp("effective_from").toInstant(),
                                r.getTimestamp("effective_to") == null
                                        ? null
                                        : r.getTimestamp("effective_to").toInstant()),
                args(tenant, codes));
    }

    private static String marks(Set<String> codes) {
        return String.join(",", Collections.nCopies(codes.size(), "?"));
    }

    private static Object[] args(String tenant, Set<String> codes) {
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(codes);
        return args.toArray();
    }
}
