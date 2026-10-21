package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rigour.erp.infrastructure.persistence.mapper.ErpWarehouseScopeMapper;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.*;

import org.springframework.stereotype.Component;

import java.util.*;

/** ERP 以仓库 ID 执行范围；调拨的两个仓库须同时符合一条完整角色条件及用户上限。 */
@Component
public final class ErpWarehouseDataScope {
    private final SupplyAuthorizationClient client;
    private final ErpWarehouseScopeMapper mapper;

    public ErpWarehouseDataScope(SupplyAuthorizationClient client, ErpWarehouseScopeMapper mapper) {
        this.client = client;
        this.mapper = mapper;
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

    public <T> LambdaQueryWrapper<T> apply(
            LambdaQueryWrapper<T> query, String action, String... fields) {
        var p = predicate(action, fields);
        StringBuilder sql = new StringBuilder();
        int i = 0;
        for (char c : p.sql().toCharArray())
            if (c == '?') sql.append('{').append(i++).append('}');
            else sql.append(c);
        return query.apply(sql.toString(), p.args().toArray());
    }

    public boolean visibleDocument(
            String tenant, long id, String table, String action, String... fields) {
        if (!Set.of(
                        "erp_inventory_warehouse",
                        "erp_procurement_order",
                        "erp_stock_in_order",
                        "erp_stock_out_order",
                        "erp_transfer_order")
                .contains(table)) throw new IllegalArgumentException("未知业务表");
        var p = predicate(action, fields);
        if ("1=1".equals(p.sql())) return true;
        var query =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object>()
                        .eq("tenant_id", tenant)
                        .eq("id", id)
                        .eq("deleted", 0);
        MybatisPlusErpCostRepository.bind(query, p.sql(), p.args());
        return mapper.countVisible(table, query) == 1;
    }

    public void requirePair(String tenant, long source, long target, String action) {
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

    private static boolean contains(Limit limit, long id) {
        return "ALL".equals(limit.mode())
                || "SPECIFIED".equals(limit.mode())
                        && limit.references().contains(Long.toString(id));
    }

    public void requireDocument(
            String tenant, long id, String table, String action, String... fields) {
        if (!visibleDocument(tenant, id, table, action, fields))
            throw new AuthorizationDeniedException(action);
    }

    public void requireWarehouse(String tenant, long id, String action) {
        if (!visibleDocument(tenant, id, "erp_inventory_warehouse", action, "id"))
            throw new AuthorizationDeniedException(action);
    }

    public void requireAll(String action) {
        var p = policy(action);
        if (p == null) return;
        boolean permitted =
                "ALL".equals(p.warehouseLimit().mode())
                        && p.clauses().stream()
                                .anyMatch(
                                        c ->
                                                "INVENTORY".equals(c.objectType())
                                                        && "ALL".equals(c.scopeMode())
                                                        && "ALL".equals(c.warehouses().mode())
                                                        && Set.of("NONE", "ALL")
                                                                .contains(c.departments().mode())
                                                        && Set.of("NONE", "ALL")
                                                                .contains(c.regions().mode()));
        if (!permitted) throw new AuthorizationDeniedException(action);
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
