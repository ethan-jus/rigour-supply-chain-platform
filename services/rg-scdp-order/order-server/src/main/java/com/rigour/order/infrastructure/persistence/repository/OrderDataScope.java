package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** 订单数据使用固化归属路径。保留每条角色完整条件，禁止地区与仓库交叉拼接。 */
@Component
public final class OrderDataScope {
    private final SupplyAuthorizationClient client;
    private final JdbcTemplate jdbc;

    public OrderDataScope(SupplyAuthorizationClient client, JdbcTemplate jdbc) {
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
        var p =
                SupplyAuthorizationContext.current().isPresent()
                        ? SupplyAuthorizationContext.requireAction(action)
                        : client.authorization(caller, action);
        if ("ACTIVE".equals(p.mode()) && !p.functionAllowed())
            throw new AuthorizationDeniedException(action);
        return "ACTIVE".equals(p.mode()) ? p : null;
    }

    /** 固定 SQL 模板改用 MyBatis 参数映射；业务输入仍只作为绑定值。 */
    public Predicate mapperPredicate(String action, String alias) {
        var p = predicate(action, alias, null);
        var sql = new StringBuilder();
        int i = 0;
        for (char c : p.sql().toCharArray()) {
            if (c == '?') sql.append("#{scope.args[").append(i++).append("]}");
            else sql.append(c);
        }
        return new Predicate(sql.toString(), p.args());
    }

    public Predicate predicate(String action, String alias, Long proposedWarehouse) {
        return predicate(action, alias, proposedWarehouse, null);
    }

    private Predicate predicate(
            String action, String alias, Long proposedWarehouse, String candidateField) {
        return predicate(action, alias, proposedWarehouse, candidateField, true);
    }

    private Predicate predicate(
            String action,
            String alias,
            Long proposedWarehouse,
            String candidateField,
            boolean draftCreator) {
        return predicate(
                policy(action), action, alias, proposedWarehouse, candidateField, draftCreator);
    }

    private Predicate predicate(
            SupplyAuthorizationView p,
            String action,
            String alias,
            Long proposedWarehouse,
            String candidateField,
            boolean draftCreator) {
        if (!alias.matches("[a-z_]*[.]?")) throw new IllegalArgumentException("固定别名无效");
        if (p == null) return new Predicate("1=1", List.of());
        List<Object> args = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        for (var c : p.clauses()) {
            if (!Set.of("ORDER", "FULFILLMENT").contains(c.objectType())
                    || "NONE".equals(c.scopeMode())) continue;
            List<Object> values = new ArrayList<>();
            List<String> and = new ArrayList<>();
            switch (c.scopeMode()) {
                case "SELF" -> {
                    if (p.employeeCode() == null) continue;
                    String self = alias + "owner_employee_code=?";
                    values.add(p.employeeCode());
                    if (draftCreator
                            && Set.of("order:read", "order:update", "order:cancel", "order:delete")
                                    .contains(action)) {
                        self =
                                "("
                                        + self
                                        + " OR ("
                                        + alias
                                        + "order_status_code='DRAFT' AND "
                                        + alias
                                        + "created_by=?))";
                        values.add(AuthorizationContext.requireCurrent().principalId().toString());
                    }
                    and.add(self);
                }
                case "DEPARTMENT" -> {
                    if ("NONE".equals(c.departments().mode())) continue;
                }
                case "REGION" -> {
                    if ("NONE".equals(c.regions().mode())) continue;
                }
                case "WAREHOUSE" -> {
                    if ("NONE".equals(c.warehouses().mode())) continue;
                }
                case "ALL" -> {}
                default -> {
                    continue;
                }
            }
            if (!"NONE".equals(c.departments().mode()))
                and.add(path(c.departments(), c.includeDescendants(), true, alias, values));
            if (!"NONE".equals(c.regions().mode()))
                and.add(path(c.regions(), c.includeDescendants(), false, alias, values));
            if (!"NONE".equals(c.warehouses().mode()))
                and.add(
                        warehouse(
                                c.warehouses(), alias, proposedWarehouse, candidateField, values));
            boolean ownership =
                    Set.of("SELF", "DEPARTMENT", "REGION").contains(c.scopeMode())
                            || !Set.of("NONE", "ALL").contains(c.departments().mode())
                            || !Set.of("NONE", "ALL").contains(c.regions().mode())
                            || ("ORDER".equals(c.objectType())
                                    && !"ALL".equals(p.regionLimit().mode()));
            if (ownership)
                and.add(
                        "("
                                + alias
                                + "order_status_code='DRAFT' OR EXISTS(SELECT 1 FROM"
                                + " order_attribution_snapshot frozen WHERE frozen.tenant_id="
                                + alias
                                + "tenant_id AND frozen.order_id="
                                + alias
                                + "id AND frozen.state='FROZEN'))");
            if (and.isEmpty()) and.add("1=1");
            clauses.add("(" + String.join(" AND ", and) + ")");
            args.addAll(values);
        }
        String role = clauses.isEmpty() ? "1=0" : "(" + String.join(" OR ", clauses) + ")";
        // 履约仍可只按仓库授权；客户地区只在该动作配置了地区条件时适用其上限。
        boolean fulfillment =
                p.clauses().stream().anyMatch(c -> "FULFILLMENT".equals(c.objectType()));
        boolean hasRegion = p.clauses().stream().anyMatch(c -> !"NONE".equals(c.regions().mode()));
        String region =
                (!fulfillment || hasRegion)
                        ? path(p.regionLimit(), true, false, alias, args)
                        : "1=1";
        boolean hasWarehouse =
                proposedWarehouse != null
                        || candidateField != null
                        || fulfillment
                        || p.clauses().stream()
                                .anyMatch(
                                        c ->
                                                !Set.of("NONE", "ALL")
                                                        .contains(c.warehouses().mode()));
        String warehouse =
                hasWarehouse
                        ? warehouse(
                                p.warehouseLimit(), alias, proposedWarehouse, candidateField, args)
                        : "1=1";
        return new Predicate("(" + role + " AND " + region + " AND " + warehouse + ")", args);
    }

    private static String path(
            Limit limit, boolean descendants, boolean department, String alias, List<Object> args) {
        if ("ALL".equals(limit.mode())) return "1=1";
        if (!"SPECIFIED".equals(limit.mode()) || limit.references().isEmpty()) return "1=0";
        List<String> matches = new ArrayList<>();
        for (String ref : limit.references()) {
            if (department && !ref.matches("[1-9][0-9]*")) continue;
            if (descendants)
                matches.add(
                        "JSON_CONTAINS(s."
                                + (department ? "department_path" : "region_path")
                                + ","
                                + (department ? "CAST(? AS JSON)" : "JSON_QUOTE(?)")
                                + ")");
            else matches.add("s." + (department ? "department_id" : "region_code") + "=?");
            args.add(ref);
        }
        if (matches.isEmpty()) return "1=0";
        return "EXISTS(SELECT 1 FROM order_attribution_snapshot s WHERE s.tenant_id="
                + alias
                + "tenant_id AND s.order_id="
                + alias
                + "id AND ("
                + String.join(" OR ", matches)
                + "))";
    }

    private static String warehouse(
            Limit limit, String alias, Long proposed, String candidateField, List<Object> args) {
        if ("ALL".equals(limit.mode())) return "1=1";
        if (!"SPECIFIED".equals(limit.mode()) || limit.references().isEmpty()) return "1=0";
        String field = candidateField == null ? alias + "selected_warehouse_id" : candidateField;
        if (proposed != null) {
            field = "?";
            args.add(proposed);
        }
        args.addAll(limit.references());
        return field
                + " IN ("
                + String.join(",", Collections.nCopies(limit.references().size(), "?"))
                + ")";
    }

    private static final Map<String, String> RELATED =
            Map.of(
                    "order_payment_record",
                    "order_id",
                    "order_refund_record",
                    "order_id",
                    "order_sales_shipment",
                    "sales_order_id",
                    "order_fund_document",
                    "related_order_id");

    public Predicate relatedPredicate(String action, String table) {
        String field = RELATED.get(table);
        if (field == null) throw new IllegalArgumentException("未知关联单据");
        var p = predicate(action, "scoped_order.", null);
        if ("1=1".equals(p.sql())) return p;
        String linked =
                "EXISTS(SELECT 1 FROM order_sales_order scoped_order WHERE scoped_order.tenant_id="
                        + table
                        + ".tenant_id AND scoped_order.id="
                        + table
                        + "."
                        + field
                        + " AND scoped_order.deleted=0 AND "
                        + p.sql()
                        + ")";
        // 非订单资金收付是真实业务类型，只有完整的全量资金授权才能读取没有原单的记录。
        if ("order_fund_document".equals(table) && unrestrictedFinancial(policy(action)))
            linked = "(" + linked + " OR " + table + ".related_order_id IS NULL)";
        return new Predicate(linked, p.args());
    }

    public <T> LambdaQueryWrapper<T> applyRelated(
            LambdaQueryWrapper<T> query, String action, String table) {
        var p = relatedPredicate(action, table);
        StringBuilder sql = new StringBuilder();
        int i = 0;
        for (char c : p.sql().toCharArray())
            if (c == '?') sql.append('{').append(i++).append('}');
            else sql.append(c);
        return query.apply(sql.toString(), p.args().toArray());
    }

    public boolean visibleRelated(String tenant, Long id, String action, String table) {
        var p = relatedPredicate(action, table);
        if ("1=1".equals(p.sql())) return true;
        if (id == null) return false;
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

    public void requireRelated(String tenant, long id, String action, String table) {
        if (!visibleRelated(tenant, id, action, table))
            throw new AuthorizationDeniedException(action);
    }

    private static boolean unrestrictedFinancial(SupplyAuthorizationView p) {
        return p != null
                && "ALL".equals(p.regionLimit().mode())
                && p.clauses().stream()
                        .anyMatch(
                                c ->
                                        "ORDER".equals(c.objectType())
                                                && "ALL".equals(c.scopeMode())
                                                && Set.of("NONE", "ALL")
                                                        .contains(c.departments().mode())
                                                && Set.of("NONE", "ALL")
                                                        .contains(c.regions().mode())
                                                && Set.of("NONE", "ALL")
                                                        .contains(c.warehouses().mode()));
    }

    public void requireRelatedOrder(String tenant, Long order, String action) {
        var p = policy(action);
        if (p == null) return;
        if (order == null) {
            if (action.startsWith("order:fund:") && unrestrictedFinancial(p)) return;
            throw new AuthorizationDeniedException("关联单据缺少当前数据范围可核验的原订单");
        }
        requireOrder(tenant, order, action);
    }

    public void requireStableAssociation(Long before, Long after) {
        if (before != null && !Objects.equals(before, after))
            throw new IllegalArgumentException("已关联原订单的单据不能直接更换原订单");
    }

    public <T> LambdaQueryWrapper<T> apply(LambdaQueryWrapper<T> query, String action) {
        var p = predicate(action, "order_sales_order.", null);
        StringBuilder sql = new StringBuilder();
        int i = 0;
        for (char c : p.sql().toCharArray())
            if (c == '?') sql.append('{').append(i++).append('}');
            else sql.append(c);
        return query.apply(sql.toString(), p.args().toArray());
    }

    public Set<Long> permittedWarehouses(String tenant, long id, List<Long> candidates) {
        if (candidates.isEmpty()) return Set.of();
        if (candidates.size() > 20000) throw new IllegalArgumentException("仓库目录超出查询上限");
        var p = predicate("order:warehouse:select", "o.", null, "candidate.id");
        List<Object> args = new ArrayList<>();
        args.add(
                tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .writeValueAsString(candidates));
        args.add(tenant);
        args.add(id);
        args.addAll(p.args());
        return new LinkedHashSet<>(
                jdbc.queryForList(
                        "SELECT candidate.id FROM order_sales_order o JOIN JSON_TABLE(?, '$[*]'"
                                + " COLUMNS(id BIGINT PATH '$')) candidate WHERE o.tenant_id=? AND"
                                + " o.id=? AND o.deleted=0 AND "
                                + p.sql(),
                        Long.class,
                        args.toArray()));
    }

    public void requireAttributedOrder(String tenant, long id, String action) {
        var p = predicate(action, "o.", null, null, false);
        List<Object> args = new ArrayList<>(List.of(tenant, id));
        args.addAll(p.args());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_sales_order o WHERE o.tenant_id=? AND o.id=?"
                                + " AND o.deleted=0 AND "
                                + p.sql(),
                        Integer.class,
                        args.toArray())
                != 1) throw new AuthorizationDeniedException("当前客户归属不在此操作授权范围内");
    }

    public void requireOrder(String tenant, long id, String action) {
        requireOrder(tenant, id, action, null);
    }

    public void compareRecord(
            String tenant, long id, String action, boolean oldAllowed, Long warehouse) {
        SupplyAuthorizationContext.compare(
                action,
                "ORDER",
                Long.toString(id),
                () ->
                        oldAllowed
                                && jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM order_sales_order WHERE"
                                                    + " tenant_id=? AND id=? AND deleted=0",
                                                Integer.class,
                                                tenant,
                                                id)
                                        == 1,
                next -> {
                    var p = predicate(next, action, "o.", warehouse, null, true);
                    var args = new ArrayList<Object>(List.of(tenant, id));
                    args.addAll(p.args());
                    return jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM order_sales_order o WHERE o.tenant_id=?"
                                            + " AND o.id=? AND o.deleted=0 AND "
                                            + p.sql(),
                                    Integer.class,
                                    args.toArray())
                            == 1;
                });
    }

    public void requireOrder(String tenant, long id, String action, Long warehouse) {
        compareRecord(tenant, id, action, true, warehouse);
        var p = predicate(action, "o.", warehouse);
        List<Object> args = new ArrayList<>(List.of(tenant, id));
        args.addAll(p.args());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_sales_order o WHERE o.tenant_id=? AND o.id=?"
                                + " AND o.deleted=0 AND "
                                + p.sql(),
                        Integer.class,
                        args.toArray())
                != 1) throw new AuthorizationDeniedException(action);
    }
}
