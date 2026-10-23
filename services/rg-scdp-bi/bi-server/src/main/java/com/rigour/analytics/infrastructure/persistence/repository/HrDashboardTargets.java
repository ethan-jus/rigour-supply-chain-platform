package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.application.model.*;
import com.rigour.analytics.application.port.out.HrTargetClient;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.*;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import com.rigour.analytics.infrastructure.persistence.scope.*;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;

import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HR owns effective goals; BI only applies its existing visibility boundary and calculates
 * attainment.
 */
@Component
public class HrDashboardTargets {
    private final HrTargetClient client;
    private final NamedParameterJdbcTemplate jdbc;
    private final SupplyDashboardQueryMapper mapper;

    public HrDashboardTargets(
            HrTargetClient client,
            javax.sql.DataSource dataSource,
            SupplyDashboardQueryMapper mapper) {
        this.client = client;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.mapper = mapper;
    }

    public List<Target> values(String tenant, String from, String to) {
        var values = client.values(tenant, from, to);
        var policy = BiScopePredicates.policy();
        if (policy == null || BiScopePredicates.unrestricted(policy)) return values;
        var visible =
                new HashSet<>(
                        BiScopedQueries.query(
                                jdbc,
                                "SELECT dimension_type,dimension_code FROM bi_target_subject WHERE"
                                        + " tenant_id=:tenant",
                                new MapSqlParameterSource("tenant", tenant),
                                (r, n) -> r.getString(1) + ":" + r.getString(2)));
        return values.stream()
                .filter(t -> visible.contains(t.dimensionType() + ":" + t.code()))
                .toList();
    }

    private Set<String> selectedRegions(String tenant, String region) {
        if (region == null) return null;
        var result = new HashSet<String>();
        result.add(region);
        result.addAll(jdbc.queryForList("SELECT region_code FROM bi_sales_contact_city_dim WHERE tenant_id=:tenant AND region_code"
                + OperatingCitySql.JDBC_REGION_SCOPE,
                new MapSqlParameterSource("tenant",tenant).addValue("region",region),String.class));
        return result;
    }

    // Parent and child goals are alternative reporting levels, never additive in the same total.
    private List<Target> reportingGoals(String tenant, List<Target> goals, String region) {
        if (goals.stream().noneMatch(t -> "CITY".equals(t.dimensionType()))) return goals;
        var selected = selectedRegions(tenant, region);
        var parents = jdbc.queryForList("SELECT child.region_code childCode,parent.region_code parentCode"
                + " FROM bi_sales_contact_city_dim child JOIN bi_sales_contact_city_dim parent"
                + " ON parent.tenant_id=child.tenant_id AND parent.department_id<>child.department_id"
                + " AND REPLACE(REPLACE(REPLACE(child.department_path,'[',','),']',','),' ','')"
                + " LIKE CONCAT('%,',parent.department_id,',%') WHERE child.tenant_id=:tenant",
                new MapSqlParameterSource("tenant", tenant));
        var available = goals.stream().filter(t -> "CITY".equals(t.dimensionType()))
                .filter(t -> selected == null || selected.contains(t.code())).toList();
        return goals.stream().filter(t -> !"CITY".equals(t.dimensionType()) ||
                ((selected == null || selected.contains(t.code())) && parents.stream().noneMatch(p ->
                    t.code().equals(p.get("childCode")) && available.stream().anyMatch(a ->
                        a.code().equals(p.get("parentCode")) && a.month().equals(t.month())
                        && a.metric().equals(t.metric()))))).toList();
    }

    public List<CityMonthlyGoal> cityGoals(String tenant, int year, String region) {
        var selected = selectedRegions(tenant, region);
        return reportingGoals(tenant, values(tenant, year + "-01", year + "-12"), region).stream()
                .filter(
                        t ->
                                "CITY".equals(t.dimensionType())
                                        && (selected == null || selected.contains(t.code())))
                .collect(
                        Collectors.groupingBy(
                                t -> t.code() + ":" + t.month(), TreeMap::new, Collectors.toList()))
                .values()
                .stream()
                .map(
                        group -> {
                            var first = group.getFirst();
                            var metrics =
                                    group.stream()
                                            .collect(
                                                    Collectors.toMap(
                                                            Target::metric, Target::value));
                            return new CityMonthlyGoal(
                                    first.code(),
                                    first.name(),
                                    YearMonth.parse(first.month()).getMonthValue(),
                                    metrics.get("SALES_AMOUNT"),
                                    metrics.get("RECEIPT_AMOUNT"),
                                    metrics.get("CONTACTED_STORE_COUNT"),
                                    metrics.get("ORDERED_STORE_COUNT"),
                                    metrics.get("YANGZHANGGUI_SALES_AMOUNT"),
                                    5);
                        })
                .toList();
    }

    public List<TargetCompletionItem> completions(
            String tenant, SupplyDashboardFilter filter, String type, List<Target> goals) {
        goals = reportingGoals(tenant, goals, filter.regionCode());
        var from = LocalDateTime.ofInstant(filter.from(), ZoneOffset.UTC);
        var to = LocalDateTime.ofInstant(filter.to(), ZoneOffset.UTC);
        boolean city = "CITY".equals(type);
        var sales =
                city
                        ? List.<Map<String, Object>>of()
                        : mapper.salesRanking(
                                tenant,
                                from,
                                to,
                                filter.regionCode(),
                                filter.ownerStaffCode(),
                                null,
                                null);
        var receipts =
                city
                        ? List.<Map<String, Object>>of()
                        : mapper.salesReceipts(
                                tenant,
                                from,
                                to,
                                filter.regionCode(),
                                filter.ownerStaffCode(),
                                null,
                                null);
        Set<String> owners = null;
        if (!city && filter.regionCode() != null) {
            owners =
                    new HashSet<>(
                            BiScopedQueries.query(
                                    jdbc,
                                    """
SELECT e.employee_code FROM bi_employee_dim e
WHERE e.tenant_id=:tenant AND e.region_code""" + OperatingCitySql.JDBC_REGION_SCOPE + """
  AND NOT EXISTS(SELECT 1 FROM bi_customer_authority a WHERE a.tenant_id=e.tenant_id
    AND a.employee_code=e.employee_code AND (a.region_code IS NULL OR NOT (a.region_code""" + OperatingCitySql.JDBC_REGION_SCOPE + """
)))
  AND NOT EXISTS(SELECT 1 FROM bi_order_authority a WHERE a.tenant_id=e.tenant_id
    AND a.employee_code=e.employee_code AND (a.region_code IS NULL OR NOT (a.region_code""" + OperatingCitySql.JDBC_REGION_SCOPE + """
)))
""",
                                    new MapSqlParameterSource("tenant", tenant)
                                            .addValue("region", filter.regionCode()),
                                    (r, n) -> r.getString(1)));
        }
        var selected = city ? selectedRegions(tenant, filter.regionCode()) : null;
        var result = new ArrayList<TargetCompletionItem>();
        var groups =
                goals.stream()
                        .filter(g -> type.equals(g.dimensionType()))
                        .filter(
                                g ->
                                        !city
                                                || filter.regionCode() == null
                                                || selected.contains(g.code()))
                        .filter(
                                g ->
                                        city
                                                || filter.ownerStaffCode() == null
                                                || filter.ownerStaffCode().equals(g.code()))
                        .collect(
                                Collectors.groupingBy(
                                        Target::code, TreeMap::new, Collectors.toList()));
        if (groups.isEmpty()) return List.of();
        Map<String, Map<String, BigDecimal>> cityAmounts = new HashMap<>();
        if (city && groups.values().stream().flatMap(Collection::stream)
                .anyMatch(g -> Set.of("SALES_AMOUNT", "RECEIPT_AMOUNT").contains(g.metric())))
            for (var row : mapper.targetCityAmounts(tenant, from, to, filter.regionCode()))
                cityAmounts.computeIfAbsent(Objects.toString(row.get("dimensionCode"), ""),
                        ignored -> new HashMap<>()).put(Objects.toString(row.get("metricCode"), ""),
                        number(row, "actualValue"));
        boolean needsBusinessActuals = groups.values().stream().flatMap(Collection::stream)
                .anyMatch(g -> Set.of("ORDERED_STORE_COUNT", "YANGZHANGGUI_SALES_AMOUNT")
                        .contains(g.metric()));
        Map<String, Map<String, BigDecimal>> businessActuals = new HashMap<>();
        if (needsBusinessActuals)
            for (var row : mapper.targetBusinessActuals(tenant, from, to, filter.regionCode(),
                    city ? null : filter.ownerStaffCode(), city))
                businessActuals.computeIfAbsent(Objects.toString(row.get("dimensionCode"), ""),
                        ignored -> new HashMap<>()).put(Objects.toString(row.get("metricCode"), ""),
                        number(row, "actualValue"));
        for (var entry : groups.entrySet()) {
            var code = entry.getKey();
            if (owners != null && !owners.contains(code)) continue;
            var metrics = entry.getValue().stream().collect(Collectors.groupingBy(Target::metric));
            for (var metric : metrics.entrySet()) {
                var targets = metric.getValue();
                var value =
                        targets.stream()
                                .map(Target::value)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                String label;
                BigDecimal actual;
                switch (metric.getKey()) {
                    case "SALES_AMOUNT" -> {
                        label = "销售总额";
                        actual = city
                                ? cityAmounts.getOrDefault(code, Map.of()).getOrDefault("SALES_AMOUNT", BigDecimal.ZERO)
                                : amount(sales, "dimensionCode", code, "salesAmount");
                    }
                    case "RECEIPT_AMOUNT" -> {
                        label = "回款额";
                        actual = city
                                ? cityAmounts.getOrDefault(code, Map.of()).getOrDefault("RECEIPT_AMOUNT", BigDecimal.ZERO)
                                : amount(
                                        receipts,
                                        city ? "regionCode" : "ownerStaffCode",
                                        code,
                                        city ? "receiptAmount" : "paidAmount");
                    }
                    case "CONTACTED_STORE_COUNT" -> {
                        label = "建联门店数";
                        // Submitted visits do not establish when a store was first added to WeCom.
                        actual = null;
                    }
                    case "ORDERED_STORE_COUNT" -> {
                        label = "有效门店数";
                        actual = businessActuals.getOrDefault(code, Map.of())
                                .getOrDefault("ORDERED_STORE_COUNT", BigDecimal.ZERO);
                    }
                    case "YANGZHANGGUI_SALES_AMOUNT" -> {
                        label = "杨掌柜品牌销售额";
                        actual = businessActuals.getOrDefault(code, Map.of())
                                .getOrDefault("YANGZHANGGUI_SALES_AMOUNT", BigDecimal.ZERO);
                    }
                    default -> throw new IllegalStateException("未知 HR 指标");
                }
                result.add(
                        new TargetCompletionItem(
                                type,
                                code,
                                targets.getFirst().name(),
                                metric.getKey(),
                                label,
                                value,
                                actual,
                                value.signum() == 0 || actual == null
                                        ? null
                                        : actual.multiply(BigDecimal.valueOf(100))
                                                .divide(value, 4, RoundingMode.HALF_UP),
                                (long) targets.size(),
                                (long) targets.size()));
            }
        }
        return result;
    }

    private static BigDecimal amount(
            List<Map<String, Object>> rows, String key, String code, String metric) {
        return rows.stream()
                .filter(r -> code.equals(r.get(key)))
                .map(r -> number(r, metric))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal number(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
