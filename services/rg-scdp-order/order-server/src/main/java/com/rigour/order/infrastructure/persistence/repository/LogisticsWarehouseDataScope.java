package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;
import com.rigour.tenant.iam.client.SupplyAuthorizationContext;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Order 物流费用按来源仓库执行范围；调拨的两个仓库须同时符合一条完整角色条件及用户上限。 */
@Component
public final class LogisticsWarehouseDataScope {
    private final SupplyAuthorizationClient client;

    public LogisticsWarehouseDataScope(SupplyAuthorizationClient client) {
        this.client = client;
    }

    public record Predicate(String sql, List<Object> args) {
        public Predicate {
            args = List.copyOf(args);
        }
    }

    public Predicate manualPredicate(String action, String object) {
        var access = com.rigour.tenant.iam.client.ManualCostScope.access(policy(action), object);
        if (!access.allowed()) return new Predicate("1=0", List.of());
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!access.allCreators()) {
            clauses.add("created_by=?");
            args.add(AuthorizationContext.requireCurrent().principalId().toString());
        }
        if (access.cities() != null) {
            if (access.cities().isEmpty()) clauses.add("1=0");
            else {
                clauses.add(
                        "city_code IN ("
                                + String.join(",", Collections.nCopies(access.cities().size(), "?"))
                                + ")");
                args.addAll(access.cities());
            }
        }
        return new Predicate(
                clauses.isEmpty() ? "1=1" : "(" + String.join(" AND ", clauses) + ")", args);
    }

    public void requireManual(String action, String object, String city) {
        com.rigour.tenant.iam.client.ManualCostScope.require(policy(action), object, city, action);
    }

    private SupplyAuthorizationView policy(String action) {
        var caller = AuthorizationContext.current().orElse(null);
        if (caller == null || !"TENANT".equals(caller.principalScope())) return null;
        var p =
                SupplyAuthorizationContext.current().isPresent()
                        ? SupplyAuthorizationContext.requireAction(action)
                        : client.authorization(caller, action);
        if (!"ACTIVE".equals(p.mode()) || !p.functionAllowed())
            throw new AuthorizationDeniedException(action);
        return p;
    }

    public Predicate predicate(String action, String... fields) {
        return predicate(policy(action), fields);
    }

    private Predicate predicate(SupplyAuthorizationView p, String... fields) {
        for (String field : fields)
            if (!field.matches("[a-z_][a-z0-9_.]*")) throw new IllegalArgumentException("固定仓库列无效");
        if (p == null) return new Predicate("1=1", List.of());
        List<String> roles = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (var c : p.clauses()) {
            if (!"INVENTORY".equals(c.objectType())
                    || !Set.of("WAREHOUSE", "ALL").contains(c.scopeMode())
                    || !Set.of("NONE", "ALL").contains(c.departments().mode())
                    || !Set.of("NONE", "ALL").contains(c.regions().mode())) continue;
            List<String> clause = new ArrayList<>();
            for (String field : fields) clause.add(in(c.warehouses(), field, args));
            roles.add("(" + String.join(" AND ", clause) + ")");
        }
        List<String> all = new ArrayList<>();
        all.add(roles.isEmpty() ? "1=0" : "(" + String.join(" OR ", roles) + ")");
        for (String field : fields) all.add(in(p.warehouseLimit(), field, args));
        return new Predicate("(" + String.join(" AND ", all) + ")", args);
    }

    private static String in(Limit limit, String field, List<Object> args) {
        if ("ALL".equals(limit.mode())) return "1=1";
        if (!"SPECIFIED".equals(limit.mode()) || limit.references().isEmpty()) return "1=0";
        args.addAll(limit.references());
        return field
                + " IN ("
                + String.join(",", Collections.nCopies(limit.references().size(), "?"))
                + ")";
    }

    public void requirePair(long source, long target, String action) {
        var policy = policy(action);
        if (policy == null) return;
        boolean allowed =
                policy.clauses().stream()
                        .anyMatch(
                                c ->
                                        "INVENTORY".equals(c.objectType())
                                                && Set.of("WAREHOUSE", "ALL")
                                                        .contains(c.scopeMode())
                                                && Set.of("NONE", "ALL")
                                                        .contains(c.departments().mode())
                                                && Set.of("NONE", "ALL")
                                                        .contains(c.regions().mode())
                                                && contains(c.warehouses(), source)
                                                && contains(c.warehouses(), target));
        if (!allowed
                || !contains(policy.warehouseLimit(), source)
                || !contains(policy.warehouseLimit(), target))
            throw new AuthorizationDeniedException(action);
    }

    private static boolean contains(Limit limit, long warehouse) {
        return "ALL".equals(limit.mode())
                || "SPECIFIED".equals(limit.mode())
                        && limit.references().contains(Long.toString(warehouse));
    }

    public void applyCity(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query,
            String action,
            String field) {
        applyCity(query, action, field, "INVENTORY");
    }

    public void applyCity(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query,
            String action,
            String field,
            String object) {
        if (!field.matches("[a-z_][a-z0-9_.]*")) throw new IllegalArgumentException("无效地区列");
        var access = com.rigour.tenant.iam.client.ManualCostScope.access(policy(action), object);
        if (!access.allowed() || access.cities() != null && access.cities().isEmpty())
            query.apply("1=0");
        else if (access.cities() != null) query.in(field, access.cities());
    }
}
