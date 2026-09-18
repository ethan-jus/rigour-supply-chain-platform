package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.BiComparisonView.City;
import com.rigour.analytics.api.v1.model.BiComparisonView.Values;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.BiComparisonStore;
import com.rigour.analytics.infrastructure.persistence.mapper.BiComparisonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 两个全量聚合共享一致性读，保持未知城市金额并独立去重全租户客户。 */
@Repository
public class MybatisBiComparisonStore implements BiComparisonStore {
    private static final Values ZERO = new Values(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
    private final BiComparisonMapper mapper;
    public MybatisBiComparisonStore(BiComparisonMapper mapper) { this.mapper = mapper; }
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot compare(String tenantId, SupplyDashboardFilter current, SupplyDashboardFilter previous) {
        var totals = rows(tenantId, current, previous, false);
        var cities = new LinkedHashMap<String, Map<String, Map<String, Object>>>();
        rows(tenantId, current, previous, true).forEach(row ->
                cities.computeIfAbsent(text(row, "regionCode"), ignored -> new LinkedHashMap<>())
                        .put(text(row, "period"), row));
        List<City> comparisons = cities.entrySet().stream().map(entry -> {
            var periods = entry.getValue();
            var latest = periods.getOrDefault("CURRENT", periods.get("PREVIOUS"));
            return new City(entry.getKey(), text(latest, "regionName"),
                    values(periods.get("CURRENT")), values(periods.get("PREVIOUS")));
        }).sorted(Comparator.comparing((City city) -> city.current().salesAmount().subtract(city.previous().salesAmount()).abs())
                .reversed().thenComparing(City::regionCode)).toList();
        return new Snapshot(period(totals, "CURRENT"), period(totals, "PREVIOUS"), comparisons);
    }
    private List<Map<String, Object>> rows(String tenant, SupplyDashboardFilter c, SupplyDashboardFilter p, boolean cities) {
        return mapper.aggregate(tenant, local(p.from()), local(c.to()), local(c.from()),
                c.regionCode(), c.ownerStaffCode(), c.customerTypeCode(), c.sourceSystemCode(), cities);
    }
    private static Values period(List<Map<String, Object>> rows, String period) {
        return rows.stream().filter(row -> period.equals(text(row, "period"))).findFirst()
                .map(MybatisBiComparisonStore::values).orElse(ZERO);
    }
    private static Values values(Map<String, Object> row) {
        return row == null ? ZERO : new Values(decimal(row, "salesAmount"), decimal(row, "paidAmount"),
                decimal(row, "unpaidAmount"), decimal(row, "orderCount").longValueExact(), decimal(row, "customerCount").longValueExact());
    }
    private static Object field(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = field(row, key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
    private static String text(Map<String, Object> row, String key) { return String.valueOf(field(row, key)); }
    private static LocalDateTime local(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
}
