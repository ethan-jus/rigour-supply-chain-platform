package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** ERP 以仓库 ID 执行范围；调拨的两个仓库须同时符合一条完整角色条件及用户上限。 */
@Component
public final class ErpWarehouseDataScope {
    private final SupplyAuthorizationClient client;
    private final JdbcTemplate jdbc;

    public ErpWarehouseDataScope(SupplyAuthorizationClient client, JdbcTemplate jdbc) {
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
        var p =
                SupplyAuthorizationContext.current().isPresent()
                        ? SupplyAuthorizationContext.requireAction(action)
                        : client.authorization(caller, action);
        if ("ACTIVE".equals(p.mode()) && !p.functionAllowed())
            throw new AuthorizationDeniedException(action);
        return "ACTIVE".equals(p.mode()) ? p : null;
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
        if ("1=1".equals(p.sql())) {
            SupplyAuthorizationContext.compare(
                    action,
                    "ERP",
                    table + ":" + id,
                    () ->
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM "
                                                    + table
                                                    + " WHERE tenant_id=? AND id=? AND deleted=0",
                                            Integer.class,
                                            tenant,
                                            id)
                                    == 1,
                    next -> {
                        var candidate = predicate(next, fields);
                        var values = new ArrayList<Object>(List.of(tenant, id));
                        values.addAll(candidate.args());
                        return jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM "
                                                + table
                                                + " WHERE tenant_id=? AND id=? AND deleted=0 AND "
                                                + candidate.sql(),
                                        Integer.class,
                                        values.toArray())
                                == 1;
                    });
            return true;
        }
        List<Object> args = new ArrayList<>(List.of(tenant, id));
        args.addAll(p.args());
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + table
                                + " WHERE tenant_id=? AND id=? AND deleted=0 AND "
                                + p.sql(),
                        Integer.class,
                        args.toArray())
                == 1;
    }

    public void requirePair(String tenant, long source, long target, String action) {
        var p = predicate(action, "w.source_id", "w.target_id");
        if ("1=1".equals(p.sql())) return;
        List<Object> args = new ArrayList<>(List.of(source, target));
        args.addAll(p.args());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT ? AS source_id,? AS target_id) w WHERE "
                                + p.sql(),
                        Integer.class,
                        args.toArray())
                != 1) throw new AuthorizationDeniedException(action);
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
}
