package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;
import com.rigour.hr.application.model.ManagerCityTarget;

import java.math.*;
import java.time.*;
import java.util.*;

/**
 * Combines monthly city goals over the employee's actual management terms. No personal fallback.
 */
public final class ManagerCityTargetResolver {
    public static final Map<String, String> METRICS =
            Map.of(
                    "CONTACTED_STORE_COUNT",
                    "建联门店数",
                    "ORDERED_STORE_COUNT",
                    "有效门店数",
                    "YANGZHANGGUI_SALES_AMOUNT",
                    "杨掌柜品牌销售额",
                    "SALES_AMOUNT",
                    "销售总额",
                    "RECEIPT_AMOUNT",
                    "回款额");

    private ManagerCityTargetResolver() {}

    public static String metric(PayrollMetricDefinition metric) {
        if (metric.cityMetric() != null && !metric.cityMetric().isBlank())
            return metric.cityMetric();
        if (METRICS.containsKey(metric.code())) return metric.code();
        // Compatibility is limited to the five exact, established business labels.
        return METRICS.entrySet().stream()
                .filter(e -> e.getValue().equals(metric.name()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    public static ManagerCityTarget resolve(
            String employee,
            String metric,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            LocalDate assessmentFrom,
            Set<LocalDate> workDays,
            String proration,
            List<PayrollRuleView> assignments,
            List<Target> targets) {
        var issues = new LinkedHashSet<String>();
        var proof = new ArrayList<String>();
        var sources = new ArrayList<Target>();
        var own =
                assignments.stream()
                        .filter(r -> employee.equals(r.data().employeeCode()))
                        .filter(
                                r ->
                                        !r.data().effectiveFrom().isAfter(month.atEndOfMonth())
                                                && !r.data().effectiveTo().isBefore(month.atDay(1)))
                        .toList();
        var days =
                month.atDay(1)
                        .datesUntil(month.atEndOfMonth().plusDays(1))
                        .filter(d -> !"WORK_DAYS".equals(proration) || workDays.contains(d))
                        .toList();
        var eligible =
                days.stream()
                        .filter(
                                d ->
                                        entry != null
                                                && !d.isBefore(entry)
                                                && (leave == null || !d.isAfter(leave))
                                                && !d.isBefore(assessmentFrom))
                        .toList();
        if (eligible.isEmpty())
            return new ManagerCityTarget(BigDecimal.ZERO, "本月无保护期外考核日期", List.of());
        if (own.isEmpty()) return new ManagerCityTarget(null, "", List.of("本月城市总管辖城市未配置"));
        var total = BigDecimal.ZERO;
        for (String city :
                own.stream().map(r -> r.data().cityCode()).distinct().sorted().toList()) {
            long count = 0;
            for (var day : eligible) {
                var current = ConfiguredCommissionService.assignmentsAt(own, city, day);
                if (current.isEmpty()) continue;
                if (current.size() != 1
                        || ConfiguredCommissionService.assignmentsAt(assignments, city, day).size()
                                != 1) issues.add(city + "：" + day + "城市总管辖任期重叠，请先核对");
                count++;
            }
            if (count == 0) continue;
            String cityName =
                    own.stream()
                            .filter(r -> city.equals(r.data().cityCode()))
                            .map(r -> r.data().cityName() == null ? city : r.data().cityName())
                            .findFirst()
                            .orElse(city);
            var candidates =
                    targets.stream()
                            .filter(
                                    t ->
                                            month.toString().equals(t.month())
                                                    && "CITY".equals(t.dimensionType())
                                                    && metric.equals(t.metric()))
                            .toList();
            var matches = candidates.stream().filter(t -> city.equals(t.code())).toList();
            if (matches.isEmpty() && cityName != null && !cityName.isBlank()) {
                // City management uses CRM codes; targets may use HR department codes.
                // Only a unique, normalized city name is accepted; never choose the first match.
                long owners =
                        own.stream()
                                .filter(
                                        r ->
                                                cityName(r.data().cityName())
                                                        .equals(cityName(cityName)))
                                .map(r -> r.data().cityCode())
                                .distinct()
                                .count();
                if (owners == 1)
                    matches =
                            candidates.stream()
                                    .filter(t -> cityName(t.name()).equals(cityName(cityName)))
                                    .toList();
            }
            if (matches.size() != 1
                    || matches.getFirst().value() == null
                    || matches.getFirst().value().signum() <= 0) {
                issues.add(
                        cityName
                                + "：请在城市指标设置维护本月"
                                + METRICS.getOrDefault(metric, metric)
                                + "目标（须大于0）");
                continue;
            }
            var target = matches.getFirst();
            sources.add(target);
            var part =
                    target.value()
                            .multiply(BigDecimal.valueOf(count))
                            .divide(BigDecimal.valueOf(days.size()), 12, RoundingMode.HALF_UP);
            total = total.add(part);
            proof.add(
                    cityName
                            + "：月目标"
                            + target.value().toPlainString()
                            + " × "
                            + count
                            + "/"
                            + days.size()
                            + "天 = "
                            + part.stripTrailingZeros().toPlainString()
                            + "（目标修订"
                            + target.revision()
                            + "）");
        }
        if (proof.isEmpty() && issues.isEmpty()) issues.add("本月考核期间没有有效管辖城市");
        if (!issues.isEmpty())
            org.slf4j.LoggerFactory.getLogger(ManagerCityTargetResolver.class)
                    .warn(
                            "Manager city goals unresolved employee={} month={} metric={}"
                                    + " issues={}",
                            employee,
                            month,
                            metric,
                            issues);
        return new ManagerCityTarget(
                issues.isEmpty() ? total : null,
                String.join("；", proof),
                List.copyOf(issues),
                List.copyOf(sources));
    }

    private static String cityName(String name) {
        return name == null ? "" : name.strip().replaceFirst("市$", "");
    }
}
