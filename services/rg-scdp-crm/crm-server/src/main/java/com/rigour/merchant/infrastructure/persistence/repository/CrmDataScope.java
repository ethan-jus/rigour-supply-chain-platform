package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** 当前 CRM 客户以主责和经营归属地区过滤，城市负责人不再叠加员工部门。 */
@Component
public final class CrmDataScope {
    private final SupplyAuthorizationClient client;
    private final JdbcTemplate jdbc;

    public CrmDataScope(SupplyAuthorizationClient client, JdbcTemplate jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    public record Predicate(String sql, List<Object> args) {
        public Predicate {
            args = List.copyOf(args);
        }
    }

    public SupplyAuthorizationView policy(String action) {
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

    public Predicate predicate(String action, String alias) {
        return predicate(policy(action), alias);
    }

    public Predicate proposedPredicate(String action, String alias, String employee) {
        return predicate(policy(action), alias, true, employee);
    }

    private Predicate predicate(SupplyAuthorizationView policy, String alias) {
        return predicate(policy, alias, false, null);
    }

    private Predicate predicate(
            SupplyAuthorizationView policy, String alias, boolean proposed, String employee) {
        if (!alias.matches("[a-z_]*[.]?")) throw new IllegalArgumentException("固定 SQL 别名无效");
        if (policy == null) return new Predicate("1=1", List.of());
        List<Object> values = new ArrayList<>();
        List<String> alternatives = new ArrayList<>();
        for (var c : policy.clauses()) {
            if (!"CUSTOMER".equals(c.objectType())
                    || !Set.of("NONE", "ALL").contains(c.departments().mode())
                    || !Set.of("NONE", "ALL").contains(c.warehouses().mode())) continue;
            List<Object> args = new ArrayList<>();
            List<String> conditions = new ArrayList<>();
            switch (c.scopeMode()) {
                case "SELF" -> {
                    if (policy.employeeCode() == null) continue;
                    if (proposed) {
                        if (!Objects.equals(employee, policy.employeeCode())) continue;
                    } else {
                        conditions.add(alias + "owner_employee_code=?");
                        args.add(policy.employeeCode());
                    }
                }
                case "REGION", "ALL" -> {}
                default -> {
                    continue;
                }
            }
            if ("NONE".equals(c.regions().mode())) continue;
            conditions.add(
                    region(
                            policy.tenantId(),
                            c.regions(),
                            c.includeDescendants(),
                            alias + "region_code",
                            args));
            alternatives.add("(" + String.join(" AND ", conditions) + ")");
            values.addAll(args);
        }
        String role =
                alternatives.isEmpty() ? "1=0" : "(" + String.join(" OR ", alternatives) + ")";
        String ceiling =
                region(
                        policy.tenantId(),
                        policy.regionLimit(),
                        true,
                        alias + "region_code",
                        values);
        return new Predicate("(" + role + " AND " + ceiling + ")", values);
    }

    /** 仅服务端生成 SQL；值全部保留为 MyBatis 参数，不能从 HTTP 传入 SQL。 */
    public record MapperPredicate(String sql, List<Object> args) {}

    public MapperPredicate partyPredicate() {
        Predicate p = predicate("crm:customer:read", "scope_customer.");
        if ("1=1".equals(p.sql())) return new MapperPredicate("1=1", List.of());
        StringBuilder sql = new StringBuilder();
        int index = 0;
        for (char c : p.sql().toCharArray())
            if (c == '?') sql.append("#{scope.args[").append(index++).append("]}");
            else sql.append(c);
        return new MapperPredicate(
                "EXISTS (SELECT 1 FROM crm_customer scope_customer WHERE"
                        + " scope_customer.tenant_id=BIN_TO_UUID(p.tenant_id) AND"
                        + " scope_customer.party_id=p.id AND scope_customer.deleted=0 AND "
                        + sql
                        + ")",
                p.args());
    }

    static String region(
            UUID tenant, Limit limit, boolean descendants, String field, List<Object> args) {
        if ("ALL".equals(limit.mode())) return "1=1";
        if (!"SPECIFIED".equals(limit.mode()) || limit.references().isEmpty()) return "1=0";
        String marks = String.join(",", Collections.nCopies(limit.references().size(), "?"));
        if (!descendants) {
            args.addAll(limit.references());
            return field + " IN (" + marks + ")";
        }
        args.add(tenant.toString());
        args.addAll(limit.references());
        args.add(tenant.toString());
        return field
                + " IN (WITH RECURSIVE scope_regions AS (SELECT area_code FROM crm_customer_area"
                + " WHERE tenant_id=UUID_TO_BIN(?) AND deleted=0 AND status='ACTIVE' AND area_code"
                + " IN ("
                + marks
                + ") UNION DISTINCT SELECT child.area_code FROM crm_customer_area child JOIN"
                + " scope_regions parent ON child.parent_area_code=parent.area_code WHERE"
                + " child.tenant_id=UUID_TO_BIN(?) AND child.deleted=0 AND child.status='ACTIVE')"
                + " SELECT area_code FROM scope_regions)";
    }

    public <T> LambdaQueryWrapper<T> apply(LambdaQueryWrapper<T> query, String action) {
        Predicate p = predicate(action, "");
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
                "CRM",
                Long.toString(id),
                () ->
                        oldAllowed
                                && jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM crm_customer WHERE"
                                                        + " tenant_id=? AND id=? AND deleted=0",
                                                Integer.class,
                                                tenant,
                                                id)
                                        == 1,
                next -> {
                    var p = predicate(next, "");
                    var args = new ArrayList<Object>(List.of(tenant, id));
                    args.addAll(p.args());
                    return jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM crm_customer WHERE tenant_id=? AND id=?"
                                            + " AND deleted=0 AND "
                                            + p.sql(),
                                    Integer.class,
                                    args.toArray())
                            == 1;
                });
    }

    public void requireCustomer(String tenant, long id, String action) {
        compareRecord(tenant, id, action, true);
        Predicate p = predicate(action, "");
        List<Object> args = new ArrayList<>(List.of(tenant, id));
        args.addAll(p.args());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crm_customer WHERE tenant_id=? AND id=? AND deleted=0"
                                + " AND "
                                + p.sql(),
                        Integer.class,
                        args.toArray())
                != 1) throw new AuthorizationDeniedException(action);
    }

    public void requireProposed(String tenant, String employee, String regionCode, String action) {
        Predicate p = predicate(action, "c.");
        List<Object> args = new ArrayList<>();
        args.add(employee);
        args.add(regionCode);
        args.addAll(p.args());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT ? AS owner_employee_code,? AS region_code) c"
                                + " WHERE "
                                + p.sql(),
                        Integer.class,
                        args.toArray())
                != 1) throw new AuthorizationDeniedException(action);
    }
}
