package com.rigour.hr.domain.payroll;

import java.math.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 自然日过程考核与当月实际回款考核；可信证据缺失只能待核对，不能作为零完成扣款。 */
public final class SalesPerformanceCalculator {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private SalesPerformanceCalculator() {}

    public record Policy(
            BigDecimal monthlyBase,
            BigDecimal bronzeBase,
            String firstMonthMode,
            int processPercent,
            int dailyCheckins,
            BigDecimal checkinPenalty,
            int dailyVisits,
            int minimumSeconds,
            BigDecimal visitPenalty,
            BigDecimal secondMonthTarget,
            BigDecimal laterMonthTarget) {}

    public record Visit(
            String id,
            LocalDate date,
            String customer,
            String status,
            Long durationMillis,
            boolean durationComplete) {}

    public record Day(
            LocalDate date,
            int checkins,
            int effectiveVisits,
            int missingCheckins,
            int missingVisits,
            BigDecimal deduction,
            boolean evidenceComplete) {}

    public record Result(
            BigDecimal processBudget,
            BigDecimal processDeduction,
            BigDecimal processPay,
            BigDecimal collectionBudget,
            BigDecimal collectionTarget,
            BigDecimal collections,
            BigDecimal collectionPay,
            BigDecimal total,
            List<Day> days,
            List<String> issues) {}

    public static Result calculate(
            Policy p,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            boolean linked,
            List<Visit> visits,
            BigDecimal collections) {
        List<String> issues = new ArrayList<>();
        List<Day> days = new ArrayList<>();
        if (entry == null || entry.isAfter(month.atEndOfMonth()))
            return new Result(
                    null,
                    null,
                    null,
                    null,
                    null,
                    collections,
                    null,
                    null,
                    List.of(),
                    List.of("入职日期缺失或晚于核算月份"));
        long serviceMonth = ChronoUnit.MONTHS.between(YearMonth.from(entry), month) + 1;
        boolean first = serviceMonth == 1;
        if (p.firstMonthMode() == null
                || first && p.bronzeBase() == null
                || (!first || !"EXEMPT_ALL".equals(p.firstMonthMode())) && p.monthlyBase() == null)
            return new Result(
                    null,
                    null,
                    null,
                    null,
                    null,
                    collections,
                    null,
                    null,
                    List.of(),
                    List.of("请完善绩效基数、铜牌金额及首月免考核口径"));
        if (first && "EXEMPT_ALL".equals(p.firstMonthMode()))
            return new Result(
                    ZERO,
                    ZERO,
                    ZERO,
                    p.bronzeBase(),
                    ZERO,
                    collections,
                    p.bronzeBase(),
                    p.bronzeBase(),
                    List.of(),
                    List.of());
        BigDecimal processBudget =
                money(
                        p.monthlyBase()
                                .multiply(BigDecimal.valueOf(p.processPercent()))
                                .divide(new BigDecimal("100")));
        BigDecimal collectionBudget =
                money(
                        (first ? p.bronzeBase() : p.monthlyBase())
                                .multiply(BigDecimal.valueOf(100 - p.processPercent()))
                                .divide(new BigDecimal("100")));
        BigDecimal processDeduction = ZERO;
        if (!linked) issues.add("销售打卡账号未关联员工，过程考核待核对");
        LocalDate from = entry.isAfter(month.atDay(1)) ? entry : month.atDay(1),
                to =
                        leave != null && leave.isBefore(month.atEndOfMonth())
                                ? leave
                                : month.atEndOfMonth();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            var current = date;
            var evidence = visits.stream().filter(v -> current.equals(v.date())).toList();
            // 同一客户同日重复打卡不增加指标，跨次短录音不拼成一次有效拜访。
            Set<String> checkins = new HashSet<>(), effective = new HashSet<>();
            boolean complete = linked;
            for (var v : evidence) {
                if ("FLAGGED".equals(v.status())) continue;
                if (!"APPROVED".equals(v.status())) {
                    complete = false;
                    continue;
                }
                if (v.customer() == null) {
                    complete = false;
                    continue;
                }
                checkins.add(v.customer());
                if (!v.durationComplete() || v.durationMillis() == null) {
                    complete = false;
                    continue;
                }
                if (v.durationMillis() >= p.minimumSeconds() * 1000L) effective.add(v.customer());
            }
            int missingCheckins = Math.max(0, p.dailyCheckins() - checkins.size()),
                    missingVisits = Math.max(0, p.dailyVisits() - effective.size());
            BigDecimal deduction =
                    complete
                            ? p.checkinPenalty()
                                    .multiply(BigDecimal.valueOf(missingCheckins))
                                    .add(
                                            p.visitPenalty()
                                                    .multiply(BigDecimal.valueOf(missingVisits)))
                            : null;
            if (complete) processDeduction = processDeduction.add(deduction);
            else issues.add(date + "：打卡审核或可信沟通时长未齐，需核对过程指标");
            days.add(
                    new Day(
                            date,
                            checkins.size(),
                            effective.size(),
                            missingCheckins,
                            missingVisits,
                            deduction,
                            complete));
        }
        BigDecimal processPay =
                issues.isEmpty() ? money(processBudget.subtract(processDeduction).max(ZERO)) : null;
        BigDecimal target =
                first ? ZERO : serviceMonth == 2 ? p.secondMonthTarget() : p.laterMonthTarget();
        BigDecimal collectionPay;
        if (first) collectionPay = collectionBudget;
        else if (collections == null) {
            issues.add("当月回款依据不完整");
            collectionPay = null;
        } else if (target == null || target.signum() <= 0) {
            issues.add("回款目标无效");
            collectionPay = null;
        } else
            collectionPay =
                    money(
                            collectionBudget
                                    .multiply(collections.max(ZERO).min(target))
                                    .divide(target, 12, RoundingMode.HALF_UP));
        BigDecimal total =
                processPay == null || collectionPay == null
                        ? null
                        : money(processPay.add(collectionPay));
        return new Result(
                processBudget,
                money(processDeduction),
                processPay,
                collectionBudget,
                target,
                collections,
                collectionPay,
                total,
                List.copyOf(days),
                List.copyOf(issues));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
