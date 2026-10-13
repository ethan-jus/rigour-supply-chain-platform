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

    public List<CityMonthlyGoal> cityGoals(String tenant, int year, String region) {
        return values(tenant, year + "-01", year + "-12").stream()
                .filter(
                        t ->
                                "CITY".equals(t.dimensionType())
                                        && (region == null || region.equals(t.code())))
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
        var from = LocalDateTime.ofInstant(filter.from(), ZoneOffset.UTC);
        var to = LocalDateTime.ofInstant(filter.to(), ZoneOffset.UTC);
        boolean city = "CITY".equals(type);
        var sales =
                city
                        ? mapper.citySalesRanking(
                                tenant, from, to, filter.regionCode(), null, null, null)
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
                        ? mapper.cityReceipts(
                                tenant, from, to, filter.regionCode(), null, null, null)
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
WHERE e.tenant_id=:tenant AND e.region_code=:region
  AND NOT EXISTS(SELECT 1 FROM bi_customer_authority a WHERE a.tenant_id=e.tenant_id
    AND a.employee_code=e.employee_code AND (a.region_code IS NULL OR a.region_code<>:region))
  AND NOT EXISTS(SELECT 1 FROM bi_order_authority a WHERE a.tenant_id=e.tenant_id
    AND a.employee_code=e.employee_code AND (a.region_code IS NULL OR a.region_code<>:region))
""",
                                    new MapSqlParameterSource("tenant", tenant)
                                            .addValue("region", filter.regionCode()),
                                    (r, n) -> r.getString(1)));
        }
        var result = new ArrayList<TargetCompletionItem>();
        var groups =
                goals.stream()
                        .filter(g -> type.equals(g.dimensionType()))
                        .filter(
                                g ->
                                        !city
                                                || filter.regionCode() == null
                                                || filter.regionCode().equals(g.code()))
                        .filter(
                                g ->
                                        city
                                                || filter.ownerStaffCode() == null
                                                || filter.ownerStaffCode().equals(g.code()))
                        .collect(
                                Collectors.groupingBy(
                                        Target::code, TreeMap::new, Collectors.toList()));
        for (var entry : groups.entrySet()) {
            var code = entry.getKey();
            if (owners != null && !owners.contains(code)) continue;
            var retention =
                    mapper.customerRetention(
                            tenant,
                            from,
                            to,
                            city ? code : filter.regionCode(),
                            city ? null : code,
                            null,
                            null);
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
                        actual = amount(sales, "dimensionCode", code, "salesAmount");
                    }
                    case "RECEIPT_AMOUNT" -> {
                        label = "回款额";
                        actual =
                                amount(
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
                        actual = number(retention, "orderingCustomerCount");
                    }
                    case "YANGZHANGGUI_SALES_AMOUNT" -> {
                        label = "杨掌柜品牌销售额";
                        actual =
                                mapper.yangzhangguiTargetSales(
                                        tenant,
                                        from,
                                        to,
                                        city ? code : filter.regionCode(),
                                        city ? null : code);
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
