package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationContext;

import java.util.*;

/** BI 数据过滤器。每条角色条件完整求交，再合并角色并应用成员上限。SQL 片段只由固定字段构造。 */
public final class BiScopePredicates {
    private BiScopePredicates() {}

    public record Sql(String text, List<Object> args) {
        public Sql {
            args = List.copyOf(args);
        }
    }

    public static SupplyAuthorizationView policy() {
        var actor = AuthorizationContext.current().orElse(null);
        if (actor == null || !"TENANT".equals(actor.principalScope())) return null;
        var context =
                SupplyAuthorizationContext.current()
                        .orElseThrow(() -> new AuthorizationDeniedException("bi-supply-context"));
        return context.active()
                ? SupplyAuthorizationContext.requireAction("analytics:dashboard:read")
                : null;
    }

    public static boolean unrestricted(SupplyAuthorizationView p) {
        return "ALL".equals(p.regionLimit().mode())
                && "ALL".equals(p.warehouseLimit().mode())
                && p.clauses().stream()
                        .anyMatch(
                                c ->
                                        "ANALYTICS".equals(c.objectType())
                                                && "ALL".equals(c.scopeMode())
                                                && unlimited(c.departments())
                                                && unlimited(c.regions())
                                                && unlimited(c.warehouses()));
    }

    private static boolean unlimited(Limit l) {
        return Set.of("ALL", "NONE").contains(l.mode());
    }

    public static Sql predicate(SupplyAuthorizationView p, String kind) {
        List<Object> args = new ArrayList<>();
        List<String> or = new ArrayList<>();
        for (var c : p.clauses()) {
            if (!p.functionAllowed()
                    || !"ANALYTICS".equals(c.objectType())
                    || "NONE".equals(c.scopeMode())) continue;
            List<Object> values = new ArrayList<>();
            List<String> and = new ArrayList<>();
            boolean people = Set.of("EMPLOYEE", "VISIT").contains(kind);
            boolean order = "ORDER".equals(kind),
                    inventory = "INVENTORY".equals(kind),
                    customer = "CUSTOMER".equals(kind),
                    object = "OBJECT".equals(kind);
            if (inventory
                    && (!Set.of("WAREHOUSE", "ALL").contains(c.scopeMode())
                            || !unlimited(c.departments())
                            || !unlimited(c.regions()))) continue;
            if (!order && !inventory && !people && !unlimited(c.departments())) continue;
            if (!order && !inventory && !unlimited(c.warehouses())) continue;
            if ("CITY".equals(kind) && !Set.of("REGION", "ALL").contains(c.scopeMode())) continue;
            switch (c.scopeMode()) {
                case "SELF" -> {
                    if (p.employeeCode() == null || inventory) continue;
                    and.add((object ? "f.employee_code" : "a.employee_code") + "=?");
                    values.add(p.employeeCode());
                }
                case "DEPARTMENT" -> {
                    if ((!order && !people) || "NONE".equals(c.departments().mode())) continue;
                }
                case "REGION" -> {
                    if (inventory || "NONE".equals(c.regions().mode())) continue;
                }
                case "WAREHOUSE" -> {
                    if ((!inventory && !order) || "NONE".equals(c.warehouses().mode())) continue;
                }
                case "ALL" -> {}
                default -> {
                    continue;
                }
            }
            if ((order || people) && !"NONE".equals(c.departments().mode()))
                and.add(
                        path(
                                c.departments(),
                                c.includeDescendants(),
                                "a.department_id",
                                "a.department_path",
                                true,
                                values));
            if (!inventory && !"NONE".equals(c.regions().mode()))
                and.add(
                        path(
                                c.regions(),
                                c.includeDescendants(),
                                object ? "f.city_code" : "a.region_code",
                                "a.region_path",
                                false,
                                values));
            if ((order || inventory) && !"NONE".equals(c.warehouses().mode()))
                and.add(
                        ids(
                                c.warehouses(),
                                inventory ? "f.warehouse_id" : "a.warehouse_id",
                                values));
            if (and.isEmpty()) and.add("1=1");
            or.add("(" + String.join(" AND ", and) + ")");
            args.addAll(values);
        }
        String roles = or.isEmpty() ? "1=0" : "(" + String.join(" OR ", or) + ")";
        String region =
                "INVENTORY".equals(kind)
                        ? "1=1"
                        : path(
                                p.regionLimit(),
                                true,
                                "OBJECT".equals(kind) ? "f.city_code" : "a.region_code",
                                "a.region_path",
                                false,
                                args);
        boolean warehouse =
                "INVENTORY".equals(kind)
                        || ("ORDER".equals(kind)
                                && p.clauses().stream()
                                        .anyMatch(
                                                c ->
                                                        !unlimited(c.warehouses())
                                                                || "WAREHOUSE"
                                                                        .equals(c.scopeMode())));
        String warehouses =
                warehouse
                        ? ids(
                                p.warehouseLimit(),
                                "INVENTORY".equals(kind) ? "f.warehouse_id" : "a.warehouse_id",
                                args)
                        : "1=1";
        String attribution =
                "ORDER".equals(kind) && !unrestricted(p) ? " AND a.attribution_state='FROZEN'" : "";
        return new Sql(
                "(" + roles + " AND " + region + " AND " + warehouses + attribution + ")", args);
    }

    private static String path(
            Limit limit,
            boolean descendants,
            String field,
            String path,
            boolean numeric,
            List<Object> args) {
        if ("ALL".equals(limit.mode())) return "1=1";
        if (!"SPECIFIED".equals(limit.mode()) || limit.references().isEmpty()) return "1=0";
        List<String> or = new ArrayList<>();
        for (String ref : limit.references()) {
            if (numeric && !ref.matches("[1-9][0-9]*")) continue;
            or.add(
                    descendants
                            ? "JSON_CONTAINS("
                                    + path
                                    + ","
                                    + (numeric ? "CAST(? AS JSON)" : "JSON_QUOTE(?)")
                                    + ")"
                            : field + "=?");
            args.add(ref);
        }
        return or.isEmpty() ? "1=0" : "(" + String.join(" OR ", or) + ")";
    }

    private static String ids(Limit l, String field, List<Object> args) {
        if ("ALL".equals(l.mode())) return "1=1";
        if (!"SPECIFIED".equals(l.mode()) || l.references().isEmpty()) return "1=0";
        args.addAll(l.references());
        return field
                + " IN ("
                + String.join(",", Collections.nCopies(l.references().size(), "?"))
                + ")";
    }

    /** 员工目标代表完整目标，只有全部已知归属都可见时才展示，不能拿部分业绩对比完整目标。 */
    private static Sql targetPredicate(SupplyAuthorizationView p) {
        if (unrestricted(p)) return new Sql("1=1", List.of());
        var city = predicate(p, "CITY");
        var employee = predicate(p, "OBJECT");
        String covered =
                employee.text()
                        .replace("f.city_code", "fp.region_code")
                        .replace("f.employee_code", "fp.employee_code")
                        .replace("a.region_path", "fp.region_path");
        var args = new ArrayList<Object>(city.args());
        args.addAll(employee.args());
        return new Sql(
                "((f.dimension_type='CITY' AND EXISTS(SELECT 1 FROM bi_region_authority a WHERE"
                        + " a.tenant_id=f.tenant_id AND a.region_code=f.dimension_code AND "
                        + city.text()
                        + ")) OR (f.dimension_type='SALES_OWNER' AND EXISTS(SELECT 1 FROM"
                        + " bi_employee_target_footprint fp WHERE fp.tenant_id=f.tenant_id AND"
                        + " fp.employee_code=f.dimension_code) AND NOT EXISTS(SELECT 1 FROM"
                        + " bi_employee_target_footprint fp WHERE fp.tenant_id=f.tenant_id AND"
                        + " fp.employee_code=f.dimension_code AND NOT COALESCE("
                        + covered
                        + ",FALSE))))",
                args);
    }

    /** 显式 CTE 覆盖所有业务事实，嵌套查询、排行、分页和汇总都只能读取过滤后的行。 */
    public static Sql ctes(SupplyAuthorizationView p) {
        List<String> ctes = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        String tenant = p.tenantId().toString();
        add(
                ctes,
                args,
                tenant,
                "bi_sales_order_fact",
                "LEFT JOIN bi_order_authority a ON a.tenant_id=f.tenant_id AND"
                        + " a.order_id=f.order_id",
                predicate(p, "ORDER"));
        add(
                ctes,
                args,
                tenant,
                "bi_sales_order_line_fact",
                "",
                new Sql(
                        "EXISTS(SELECT 1 FROM bi_sales_order_fact o WHERE o.tenant_id=f.tenant_id"
                                + " AND o.order_id=f.order_id)",
                        List.of()));
        add(
                ctes,
                args,
                tenant,
                "bi_sales_payment_fact",
                "",
                new Sql(
                        "EXISTS(SELECT 1 FROM bi_sales_order_fact o WHERE o.tenant_id=f.tenant_id"
                                + " AND o.order_id=f.order_id)",
                        List.of()));
        add(
                ctes,
                args,
                tenant,
                "bi_customer_dim",
                "LEFT JOIN bi_customer_authority a ON a.tenant_id=f.tenant_id AND"
                        + " a.customer_id=f.customer_id",
                predicate(p, "CUSTOMER"));
        var employee = predicate(p, "EMPLOYEE");
        add(
                ctes,
                args,
                tenant,
                "bi_employee_dim",
                "LEFT JOIN bi_region_authority a ON a.tenant_id=f.tenant_id AND"
                    + " a.region_code=f.region_code",
                new Sql(
                        employee.text()
                                .replace("a.employee_code", "f.employee_code")
                                .replace("a.department_id", "f.department_id")
                                .replace("a.department_path", "f.department_path"),
                        employee.args()));
        var visit = predicate(p, "VISIT");
        add(
                ctes,
                args,
                tenant,
                "bi_sales_contact_fact",
                "LEFT JOIN bi_region_authority a ON a.tenant_id=f.tenant_id AND"
                    + " a.region_code=f.region_code LEFT JOIN bi_source_hr_hr_employee e ON"
                    + " e.tenant_id=f.tenant_id AND e.employee_code=f.owner_staff_code",
                new Sql(
                        visit.text()
                                .replace("a.employee_code", "f.owner_staff_code")
                                .replace("a.department_id", "e.department_id")
                                .replace("a.department_path", "e.department_path"),
                        visit.args()));
        var city = predicate(p, "CITY");
        add(
                ctes,
                args,
                tenant,
                "bi_sales_contact_city_dim",
                "LEFT JOIN bi_region_authority a ON a.tenant_id=f.tenant_id AND"
                    + " a.region_code=f.region_code",
                new Sql(
                        "("
                                + city.text()
                                + " OR EXISTS(SELECT 1 FROM bi_sales_contact_fact v WHERE"
                                + " v.tenant_id=f.tenant_id AND v.region_code=f.region_code))",
                        city.args()));
        add(
                ctes,
                args,
                tenant,
                "bi_customer_attribute_current",
                "",
                new Sql(
                        "EXISTS(SELECT 1 FROM bi_customer_dim c WHERE c.tenant_id=f.tenant_id AND"
                            + " c.customer_id=f.customer_id)",
                        List.of()));
        for (String table : List.of("bi_inventory_balance_current", "bi_inventory_operation_fact"))
            add(ctes, args, tenant, table, "", predicate(p, "INVENTORY"));
        add(
                ctes,
                args,
                tenant,
                "bi_city_cost_record",
                "LEFT JOIN bi_region_authority a ON a.tenant_id=f.tenant_id AND"
                        + " a.region_code=f.region_code",
                predicate(p, "CITY"));
        add(
                ctes,
                args,
                tenant,
                "bi_operating_action",
                "LEFT JOIN bi_region_authority a ON a.tenant_id=f.tenant_id AND"
                        + " a.region_code=f.city_code",
                predicate(p, "OBJECT"));
        add(
                ctes,
                args,
                tenant,
                "bi_operating_action_event",
                "",
                new Sql(
                        "EXISTS(SELECT 1 FROM bi_operating_action v WHERE v.tenant_id=f.tenant_id"
                                + " AND v.id=f.action_id)",
                        List.of()));
        ctes.add(
                "bi_employee_target_footprint AS (SELECT"
                    + " tenant_id,employee_code,region_code,region_path FROM bi_customer_authority"
                    + " WHERE tenant_id=? UNION SELECT"
                    + " tenant_id,employee_code,region_code,region_path FROM bi_order_authority"
                    + " WHERE tenant_id=?)");
        args.add(tenant);
        args.add(tenant);
        add(ctes, args, tenant, "bi_business_target", "", targetPredicate(p));
        for (String table :
                List.of(
                        "bi_reconciliation_review",
                        "bi_supply_reconciliation_current",
                        "bi_feishu_legacy_archive",
                        "bi_etl_run",
                        "bi_etl_checkpoint"))
            add(ctes, args, tenant, table, "", new Sql(unrestricted(p) ? "1=1" : "1=0", List.of()));
        for (String table :
                List.of("bi_product_dim", "bi_product_category_dim", "bi_product_category_closure"))
            add(ctes, args, tenant, table, "", new Sql("1=1", List.of()));
        return new Sql(String.join(",", ctes), args);
    }

    private static void add(
            List<String> ctes,
            List<Object> args,
            String tenant,
            String table,
            String join,
            Sql filter) {
        ctes.add(
                table
                        + " AS (SELECT f.* FROM "
                        + table
                        + " f "
                        + join
                        + " WHERE f.tenant_id=? AND "
                        + filter.text()
                        + ")");
        args.add(tenant);
        args.addAll(filter.args());
    }
}
