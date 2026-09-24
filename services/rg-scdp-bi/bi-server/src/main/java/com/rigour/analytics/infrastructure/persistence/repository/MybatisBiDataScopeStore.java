package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.infrastructure.persistence.mapper.BiDataScopeMapper;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** 通过统一角色条件查询 BI 本地事实。 */
@Repository
public class MybatisBiDataScopeStore implements BiDataScopeStore {
    private final BiDataScopeMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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
