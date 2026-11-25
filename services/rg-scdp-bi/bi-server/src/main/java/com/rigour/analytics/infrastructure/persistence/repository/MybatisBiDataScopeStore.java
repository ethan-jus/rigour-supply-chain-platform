package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.infrastructure.persistence.mapper.BiDataScopeMapper;

import org.springframework.stereotype.Repository;

import com.rigour.analytics.api.v1.model.DepartmentDashboardFilters;
import com.rigour.analytics.api.v1.model.DepartmentDashboardFilters.Node;
import com.rigour.analytics.infrastructure.persistence.scope.BiScopedQueries;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

/** 通过统一角色条件查询 BI 本地事实。 */
@Repository
public class MybatisBiDataScopeStore implements BiDataScopeStore {
    private final BiDataScopeMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rigour.shared.cache.QueryCache queryCache = com.rigour.shared.cache.QueryCache.disabled();

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    public DepartmentDashboardFilters departmentFilters(String tenant, String employee) {
        return queryCache.read(tenant, "departmentDashboardFilters", java.util.Arrays.asList(employee), 60,
                new tools.jackson.core.type.TypeReference<>() {}, () -> loadDepartmentFilters(tenant, employee));
    }

    private DepartmentDashboardFilters loadDepartmentFilters(String tenant, String employee) {
        var named = new NamedParameterJdbcTemplate(jdbc);
        var params = new MapSqlParameterSource("tenant", tenant).addValue("employee", employee);
        record Department(Node node, boolean managed) {}
        var rows = BiScopedQueries.query(named, """
                SELECT c.region_code,c.city_name,
                  (SELECT p.region_code FROM bi_sales_contact_city_dim p
                    WHERE p.tenant_id=c.tenant_id AND p.department_id<>c.department_id
                      AND JSON_CONTAINS(c.department_path,CAST(p.department_id AS JSON))
                    ORDER BY JSON_LENGTH(p.department_path) DESC LIMIT 1) parent_code,
                  d.leader_employee_code=:employee AS managed
                FROM bi_sales_contact_city_dim c
                LEFT JOIN bi_source_hr_hr_department d ON d.tenant_id=c.tenant_id AND d.id=c.department_id
                WHERE c.tenant_id=:tenant ORDER BY JSON_LENGTH(c.department_path),c.city_name,c.region_code
                """, params, (r,i) -> new Department(new Node(r.getString(1),r.getString(2),r.getString(3)),r.getBoolean(4)));
        var regions = BiScopedQueries.query(named, """
                WITH visible_paths AS (
                    SELECT DISTINCT a.region_code,CAST(a.region_path AS CHAR) region_path
                      FROM bi_sales_order_fact o JOIN bi_order_authority a
                        ON a.tenant_id=o.tenant_id AND a.order_id=o.order_id
                     WHERE o.tenant_id=:tenant AND o.deleted=0
                )
                SELECT d.area_code,d.area_name,d.parent_area_code FROM bi_source_crm_crm_customer_area d
                WHERE d.tenant_id=UUID_TO_BIN(:tenant) AND d.deleted=0
                  AND EXISTS (SELECT 1 FROM visible_paths a
                    WHERE a.region_code=d.area_code OR JSON_CONTAINS(a.region_path,JSON_QUOTE(d.area_code)))
                ORDER BY d.area_name,d.area_code
                """,params,(r,i)->new Node(r.getString(1),r.getString(2),r.getString(3)));
        return new DepartmentDashboardFilters(rows.stream().map(Department::node).toList(), regions,
                rows.stream().filter(Department::managed).map(row -> row.node().code()).findFirst().orElse(null));
    }

    @Override
    public boolean appObjectVisible(String tenant, String city, String employee, String action) {
        var p = com.rigour.tenant.iam.client.SupplyAuthorizationContext.requireAction(action);
        if (p == null) return false;
        var predicate =
                com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates.predicate(
                        p, "OBJECT");
        var args = new java.util.ArrayList<Object>();
        args.add(city);
        args.add(employee);
        args.add(tenant);
        args.addAll(predicate.args());
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT ? AS city_code, ? AS employee_code) f LEFT"
                                + " JOIN bi_region_authority a ON a.tenant_id=? AND"
                                + " a.region_code=f.city_code WHERE "
                                + predicate.text(),
                        Integer.class,
                        args.toArray())
                == 1;
    }

    public MybatisBiDataScopeStore(BiDataScopeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SupplyDashboardFilterOptionsView filterOptions(
            String tenantId, List<String> regions, String owner) {
        if (regions == null) {
            if (com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates.policy()
                    == null) throw new IllegalArgumentException("需要应用数据范围");
        } else if (regions.isEmpty())
            throw new IllegalArgumentException("At least one authorized region is required");
        var options =
                mapper.filterOptions(tenantId, regions, owner).stream()
                        .map(
                                row ->
                                        new SupplyDashboardFilterOptionView(
                                                text(row, "optionType"),
                                                text(row, "optionValue"),
                                                label(row),
                                                null))
                        .toList();
        return new SupplyDashboardFilterOptionsView(
                ofType(options, "REGION"),
                ofType(options, "SALES_OWNER"),
                ofType(options, "CUSTOMER_TYPE"),
                ofType(options, "PRODUCT_CATEGORY"),
                ofType(options, "SOURCE_SYSTEM"));
    }

    private static String label(Map<String, Object> row) {
        String value = text(row, "optionValue");
        if ("SOURCE_SYSTEM".equals(text(row, "optionType"))) {
            return switch (value) {
                case "FEISHU" -> "飞书";
                case "DINGHUOBAO" -> "订货宝";
                case "MANUAL" -> "手工录入";
                default -> value;
            };
        }
        String label = text(row, "optionLabel");
        return label == null || label.isBlank() ? value : label;
    }

    private static List<SupplyDashboardFilterOptionView> ofType(
            List<SupplyDashboardFilterOptionView> rows, String type) {
        return rows.stream().filter(row -> type.equals(row.optionType())).toList();
    }

    private static Object value(Map<String, Object> row, String key) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String text(Map<String, Object> row, String key) {
        var value = value(row, key);
        return value == null ? null : value.toString();
    }
}
