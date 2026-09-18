package com.rigour.hr.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** HR 独立构造本库员工过滤，在计数、分页及详情之前执行当前部门规则。 */
@Component
public final class HrDataScope {
    private final SupplyAuthorizationClient client;
    private final JdbcTemplate jdbc;

    public HrDataScope(SupplyAuthorizationClient client, JdbcTemplate jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    public record Predicate(String sql, List<Object> args) {
        public Predicate {
            args = List.copyOf(args);
        }
    }

    private SupplyAuthorizationView policy(String action) {
        var caller = AuthorizationContext.current().orElse(null);
        if (caller == null || !"TENANT".equals(caller.principalScope())) return null;
        var result =
                SupplyAuthorizationContext.current().isPresent()
                        ? SupplyAuthorizationContext.requireAction(action)
                        : client.authorization(caller, action);
        if ("ACTIVE".equals(result.mode()) && !result.functionAllowed())
            throw new AuthorizationDeniedException(action);
        return "ACTIVE".equals(result.mode()) ? result : null;
    }

    public Predicate employeePredicate(String action, String alias) {
        return employeePredicate(policy(action), alias);
    }

    private Predicate employeePredicate(SupplyAuthorizationView policy, String alias) {
        if (!alias.matches("[a-z]*[.]?")) throw new IllegalArgumentException("固定 SQL 别名无效");
        if (policy == null) return new Predicate("1=1", List.of());
        List<Object> args = new ArrayList<>();
        List<String> alternatives = new ArrayList<>();
        for (var c : policy.clauses()) {
            if (!"EMPLOYEE".equals(c.objectType())) continue;
            if (!Set.of("NONE", "ALL").contains(c.regions().mode())
                    || !Set.of("NONE", "ALL").contains(c.warehouses().mode())) continue;
            List<String> conditions = new ArrayList<>();
            switch (c.scopeMode()) {
                case "SELF" -> {
                    if (policy.employeeCode() == null) continue;
                    conditions.add(alias + "employee_code=?");
                    args.add(policy.employeeCode());
                }
                case "DEPARTMENT" -> {
                    if ("NONE".equals(c.departments().mode())) continue;
                }
                case "ALL" -> {}
                default -> {
                    continue;
                }
            }
            if (!"NONE".equals(c.departments().mode()))
                conditions.add(
                        departmentCondition(
                                policy,
                                c.departments(),
                                c.includeDescendants(),
                                alias + "department_id",
                                args));
            alternatives.add(
                    conditions.isEmpty() ? "1=1" : "(" + String.join(" AND ", conditions) + ")");
        }
        return new Predicate(
                alternatives.isEmpty() ? "1=0" : "(" + String.join(" OR ", alternatives) + ")",
                args);
    }

    private static String departmentCondition(
            SupplyAuthorizationView policy,
            Limit limit,
            boolean children,
            String field,
            List<Object> args) {
        if ("ALL".equals(limit.mode())) return "1=1";
        if (!"SPECIFIED".equals(limit.mode()) || limit.references().isEmpty()) return "1=0";
        String marks = String.join(",", Collections.nCopies(limit.references().size(), "?"));
        if (children) {
            args.add(policy.tenantId().toString());
            args.addAll(limit.references());
            return field
                    + " IN (SELECT scope_department.descendant_id FROM hr_department_closure"
                    + " scope_department WHERE scope_department.tenant_id=? AND"
                    + " scope_department.ancestor_id IN ("
                    + marks
                    + "))";
        }
        args.addAll(limit.references());
        return field + " IN (" + marks + ")";
    }

    public <T> QueryWrapper<T> apply(QueryWrapper<T> query, String action) {
        Predicate p = employeePredicate(action, "");
        StringBuilder sql = new StringBuilder();
        int i = 0;
        for (char c : p.sql().toCharArray())
            if (c == '?') sql.append('{').append(i++).append('}');
            else sql.append(c);
        return query.apply(sql.toString(), p.args().toArray());
    }

    public void compareRecord(String tenant, long id, String action, boolean oldAllowed) {
        SupplyAuthorizationContext.compare(
                action,
                "HR",
                Long.toString(id),
                () ->
                        oldAllowed
                                && jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM hr_employee WHERE tenant_id=?"
                                                    + " AND id=? AND deleted=0",
                                                Integer.class,
                                                tenant,
                                                id)
                                        == 1,
                next -> {
                    var p = employeePredicate(next, "");
                    var args = new ArrayList<Object>(List.of(tenant, id));
                    args.addAll(p.args());
                    return jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM hr_employee WHERE tenant_id=? AND id=?"
                                            + " AND deleted=0 AND "
                                            + p.sql(),
                                    Integer.class,
                                    args.toArray())
                            == 1;
                });
    }

    public void requireEmployee(String tenant, long id, String action) {
        compareRecord(tenant, id, action, true);
        Predicate p = employeePredicate(action, "");
        List<Object> args = new ArrayList<>(List.of(tenant, id));
        args.addAll(p.args());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM hr_employee WHERE tenant_id=? AND id=? AND deleted=0"
                                + " AND "
                                + p.sql(),
                        Integer.class,
                        args.toArray())
                != 1) throw new AuthorizationDeniedException(action);
    }

    public boolean canReadCode(String tenant, String code) {
        Predicate p = employeePredicate("hr:employee:read", "");
        List<Object> args = new ArrayList<>(List.of(tenant, code));
        args.addAll(p.args());
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM hr_employee WHERE tenant_id=? AND employee_code=? AND"
                                + " deleted=0 AND "
                                + p.sql(),
                        Integer.class,
                        args.toArray())
                == 1;
    }

    public void requireDepartment(String tenant, long department, String action) {
        var policy = policy(action);
        if (policy == null) return;
        List<Object> args = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        for (var c : policy.clauses())
            if ("EMPLOYEE".equals(c.objectType())
                    && Set.of("ALL", "DEPARTMENT").contains(c.scopeMode())) {
                if ("ALL".equals(c.scopeMode()) && "NONE".equals(c.departments().mode()))
                    clauses.add("1=1");
                else
                    clauses.add(
                            departmentCondition(
                                    policy, c.departments(), c.includeDescendants(), "id", args));
            }
        List<Object> values = new ArrayList<>(List.of(tenant, department));
        values.addAll(args);
        String condition = clauses.isEmpty() ? "1=0" : "(" + String.join(" OR ", clauses) + ")";
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM hr_department WHERE tenant_id=? AND id=? AND"
                                + " status_code='ACTIVE' AND deleted=0 AND "
                                + condition,
                        Integer.class,
                        values.toArray())
                != 1) throw new AuthorizationDeniedException(action);
    }
}
