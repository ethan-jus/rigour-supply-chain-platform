package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** 仅使用已验证规则与事实，不读数据库，也不猜测缺失指标。 */
public final class SalarySchemeCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private SalarySchemeCalculator() {}

    public static boolean contains(
            BigDecimal value,
            BigDecimal lower,
            BigDecimal upper,
            boolean lowerInclusive,
            boolean upperInclusive) {
        if (value == null || lower == null) return false;
        int low = value.compareTo(lower);
        return (low > 0 || low == 0 && lowerInclusive)
                && (upper == null
                        || value.compareTo(upper) < 0
                        || value.compareTo(upper) == 0 && upperInclusive);
    }

    public static SalaryBandResult salary(
            List<SalaryBand> bands, BigDecimal achievement, String protectedBand) {
        if (achievement == null && protectedBand == null)
            return new SalaryBandResult(null, null, List.of("回款业绩依据待核对，暂不能确定薪资档位"));
        var matches =
                bands.stream()
                        .filter(
                                b ->
                                        protectedBand != null
                                                ? protectedBand.equals(b.code())
                                                : contains(
                                                        achievement,
                                                        b.lower(),
                                                        b.upper(),
                                                        b.lowerInclusive(),
                                                        b.upperInclusive()))
                        .toList();
        if (matches.size() != 1) return new SalaryBandResult(null, null, List.of("底薪档位未匹配或存在冲突"));
        return new SalaryBandResult(
                matches.getFirst().code(), money(matches.getFirst().amount()), List.of());
    }

    public static Set<LocalDate> workDates(
            YearMonth month, List<Integer> weekdays, List<LocalDate> work, List<LocalDate> rest) {
        return workDates(month, weekdays, work, rest, List.of(), null);
    }

    public static Set<LocalDate> workDates(
            YearMonth month,
            List<Integer> weekdays,
            List<LocalDate> work,
            List<LocalDate> rest,
            List<Integer> alternate,
            LocalDate anchor) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (LocalDate day = month.atDay(1);
                !day.isAfter(month.atEndOfMonth());
                day = day.plusDays(1)) {
            boolean alternateWeek =
                    anchor != null
                            && Math.floorMod(
                                            java.time.temporal.ChronoUnit.WEEKS.between(
                                                    anchor.with(
                                                            java.time.temporal.TemporalAdjusters
                                                                    .previousOrSame(
                                                                            DayOfWeek.MONDAY)),
                                                    day.with(
                                                            java.time.temporal.TemporalAdjusters
                                                                    .previousOrSame(
                                                                            DayOfWeek.MONDAY))),
                                            2)
                                    == 1;
            var schedule = alternateWeek ? alternate : weekdays;
            if ((schedule.contains(day.getDayOfWeek().getValue()) || work.contains(day))
                    && !rest.contains(day)) dates.add(day);
        }
        return Set.copyOf(dates);
    }

    /** 窗口为左闭右开；午夜后的记录先判断是否属于前一工作日。 */
    public static LocalDate businessDate(
            LocalDateTime time, LocalTime start, LocalTime end, boolean nextDay, String outside) {
        LocalTime clock = time.toLocalTime();
        if (nextDay) {
            if (clock.isBefore(end)) return time.toLocalDate().minusDays(1);
            if (!clock.isBefore(start)) return time.toLocalDate();
        } else if (!clock.isBefore(start) && clock.isBefore(end)) return time.toLocalDate();
        return "CALENDAR_DAY".equals(outside) ? time.toLocalDate() : null;
    }

    /** 返回保护期结束的排他日期；月末按 java.time 的日历月末截断处理。 */
    public static LocalDate protectionUntil(LocalDate entry, String unit, int count) {
        if (entry == null) return null;
        return "MONTHS".equals(unit) ? entry.plusMonths(count) : entry.plusDays(count);
    }

    public static BigDecimal proration(
            String mode,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            Set<LocalDate> workDays,
            BigDecimal attendanceDays,
            BigDecimal expectedDays) {
        if (entry != null && entry.isAfter(month.atEndOfMonth())
                || leave != null && leave.isBefore(month.atDay(1))) return BigDecimal.ZERO;
        if ("NONE".equals(mode)) return BigDecimal.ONE;
        if (entry == null) return null;
        if ("CONFIRMED_ATTENDANCE".equals(mode)) {
            if (attendanceDays == null || expectedDays == null || expectedDays.signum() <= 0)
                return null;
            return attendanceDays
                    .max(BigDecimal.ZERO)
                    .divide(expectedDays, 12, RoundingMode.HALF_UP)
                    .min(BigDecimal.ONE);
        }
        List<LocalDate> eligible =
                month.atDay(1)
                        .datesUntil(month.atEndOfMonth().plusDays(1))
                        .filter(d -> !"WORK_DAYS".equals(mode) || workDays.contains(d))
                        .toList();
        if (eligible.isEmpty()) return null;
        long count =
                eligible.stream()
                        .filter(d -> !d.isBefore(entry) && (leave == null || !d.isAfter(leave)))
                        .count();
        return BigDecimal.valueOf(count)
                .divide(BigDecimal.valueOf(eligible.size()), 12, RoundingMode.HALF_UP);
    }

    public static ManagerPerformanceResult manager(
            BigDecimal base,
            List<ManagerMetric> metrics,
            List<PerformanceBand> steps,
            BigDecimal cap,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            LocalDate protectedUntil,
            boolean assessUnprotectedOnly,
            Set<LocalDate> countingDays,
            String proration) {
        return manager(
                base,
                metrics,
                steps,
                cap,
                month,
                entry,
                leave,
                protectedUntil,
                assessUnprotectedOnly,
                countingDays,
                proration,
                "TIERED");
    }

    public static ManagerPerformanceResult manager(
            BigDecimal base,
            List<ManagerMetric> metrics,
            List<PerformanceBand> steps,
            BigDecimal cap,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            LocalDate protectedUntil,
            boolean assessUnprotectedOnly,
            Set<LocalDate> countingDays,
            String proration,
            String calculationMode) {
        List<String> issues = new ArrayList<>();
        List<ManagerMetricResult> details = new ArrayList<>();
        if (entry == null || base == null || protectedUntil == null)
            return new ManagerPerformanceResult(
                    null, null, null, 0, 0, List.of(), List.of("城市总入职日期或绩效基数缺失"));
        var dates =
                month.atDay(1)
                        .datesUntil(month.atEndOfMonth().plusDays(1))
                        .filter(d -> !"WORK_DAYS".equals(proration) || countingDays.contains(d))
                        .toList();
        long employed =
                dates.stream()
                        .filter(d -> !d.isBefore(entry) && (leave == null || !d.isAfter(leave)))
                        .count();
        long protectedDays =
                dates.stream()
                        .filter(
                                d ->
                                        !d.isBefore(entry)
                                                && d.isBefore(protectedUntil)
                                                && (leave == null || !d.isAfter(leave)))
                        .count();
        long assessed = employed - protectedDays;
        if (dates.isEmpty())
            return new ManagerPerformanceResult(
                    null, null, null, 0, 0, List.of(), List.of("月度折算分母为0"));
        BigDecimal completionTotal = BigDecimal.ZERO;
        BigDecimal scoredTotal = BigDecimal.ZERO;
        if (assessed > 0)
            for (var metric : metrics) {
                if (metric.weight().signum() == 0) continue;
                if (metric.actual() == null
                        || metric.target() == null
                        || metric.target().signum() <= 0) {
                    issues.add(metric.name() + "：实际值或目标待核定");
                    continue;
                }
                BigDecimal target = metric.target();
                if (assessUnprotectedOnly && !metric.targetProrated())
                    target =
                            target.multiply(BigDecimal.valueOf(assessed))
                                    .divide(
                                            BigDecimal.valueOf(dates.size()),
                                            12,
                                            RoundingMode.HALF_UP);
                BigDecimal completion =
                        metric.actual()
                                .max(BigDecimal.ZERO)
                                .divide(target, 12, RoundingMode.HALF_UP);
                completionTotal =
                        completionTotal.add(completion.multiply(metric.weight()).divide(HUNDRED));
                BigDecimal score = completion;
                if ("ACTUAL_COMPLETION".equals(calculationMode)
                        && "TIERED".equals(metric.scoringMode())) {
                    var bands =
                            metric.bands() == null ? List.<PerformanceBand>of() : metric.bands();
                    var matched =
                            bands.stream()
                                    .filter(
                                            b ->
                                                    contains(
                                                            completion,
                                                            b.lower(),
                                                            b.upper(),
                                                            b.lowerInclusive(),
                                                            b.upperInclusive()))
                                    .toList();
                    if (matched.isEmpty()
                            || matched.stream()
                                    .anyMatch(
                                            b ->
                                                    b.coefficient() == null
                                                            || b.coefficient().signum() < 0)) {
                        issues.add(metric.name() + "：完成率未匹配有效计分区间或区间系数无效，请核对单项阶梯规则");
                        continue;
                    }
                    // Overlapping single-metric ranges use the highest calculated score,
                    // so a larger multiplier alone does not necessarily win over a fixed score.
                    score =
                            matched.stream()
                                    .map(
                                            band ->
                                                    "LINEAR".equals(band.mode())
                                                            ? completion.multiply(
                                                                    band.coefficient())
                                                            : band.coefficient())
                                    .max(BigDecimal::compareTo)
                                    .orElseThrow();
                }
                BigDecimal contribution = score.multiply(metric.weight()).divide(HUNDRED);
                scoredTotal = scoredTotal.add(contribution);
                details.add(
                        new ManagerMetricResult(
                                metric.code(),
                                metric.actual(),
                                target,
                                completion,
                                "ACTUAL_COMPLETION".equals(calculationMode) ? score : null,
                                contribution));
            }
        BigDecimal coefficient = BigDecimal.ONE;
        if (assessed > 0 && issues.isEmpty() && "ACTUAL_COMPLETION".equals(calculationMode)) {
            coefficient = scoredTotal;
        } else if (assessed > 0 && issues.isEmpty()) {
            var completion = completionTotal;
            var matching =
                    steps == null
                            ? List.<PerformanceBand>of()
                            : steps.stream()
                                    .filter(
                                            b ->
                                                    contains(
                                                            completion,
                                                            b.lower(),
                                                            b.upper(),
                                                            b.lowerInclusive(),
                                                            b.upperInclusive()))
                                    .toList();
            if (matching.size() != 1) issues.add("综合完成率未匹配唯一绩效系数，请核对城市总绩效规则");
            else {
                var band = matching.getFirst();
                coefficient =
                        ("LINEAR".equals(band.mode())
                                        ? completion.multiply(band.coefficient())
                                        : band.coefficient())
                                .min(cap);
            }
        }
        // NONE不做入离职折算，但保护期和非保护期仍按在职天数加权。
        long denominator = "NONE".equals(proration) ? employed : dates.size();
        if (denominator == 0) issues.add("核算月份没有在职计薪日");
        BigDecimal amount =
                !issues.isEmpty()
                        ? null
                        : money(
                                base.multiply(
                                                BigDecimal.valueOf(protectedDays)
                                                        .add(
                                                                BigDecimal.valueOf(assessed)
                                                                        .multiply(coefficient)))
                                        .divide(
                                                BigDecimal.valueOf(denominator),
                                                12,
                                                RoundingMode.HALF_UP));
        return new ManagerPerformanceResult(
                amount,
                issues.isEmpty() ? coefficient : null,
                assessed > 0 && issues.isEmpty() ? completionTotal : null,
                (int) protectedDays,
                (int) assessed,
                List.copyOf(details),
                List.copyOf(issues));
    }

    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
