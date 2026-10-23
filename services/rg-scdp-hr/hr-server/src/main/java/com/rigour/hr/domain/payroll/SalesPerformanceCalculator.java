package com.rigour.hr.domain.payroll;

import java.math.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 按日核算合格打卡和有效拜访；记录未达资格按规则扣减，来源未关联不能视为零完成。 */
public final class SalesPerformanceCalculator {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private SalesPerformanceCalculator() {}

    public static SalesPerformanceResult calculate(
            SalesPerformancePolicy p,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            boolean linked,
            List<SalesVisitEvidence> visits,
            BigDecimal collections) {
        return calculate(p, month, entry, leave, linked, visits, collections, null, BigDecimal.ONE);
    }

    public static SalesPerformanceResult calculate(
            SalesPerformancePolicy p,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            boolean linked,
            List<SalesVisitEvidence> visits,
            BigDecimal collections,
            Set<LocalDate> assessmentDays,
            BigDecimal resultCap) {
        return calculate(
                p,
                month,
                entry,
                leave,
                linked,
                visits,
                collections,
                assessmentDays,
                resultCap,
                BigDecimal.valueOf(p.processPercent()));
    }

    public static SalesPerformanceResult calculate(
            SalesPerformancePolicy p,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            boolean linked,
            List<SalesVisitEvidence> visits,
            BigDecimal collections,
            Set<LocalDate> assessmentDays,
            BigDecimal resultCap,
            BigDecimal processPercent) {
        return calculate(
                p,
                month,
                entry,
                leave,
                linked,
                visits,
                collections,
                assessmentDays,
                resultCap,
                processPercent,
                "UNIQUE_CUSTOMER");
    }

    public static SalesPerformanceResult calculate(
            SalesPerformancePolicy p,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            boolean linked,
            List<SalesVisitEvidence> visits,
            BigDecimal collections,
            Set<LocalDate> assessmentDays,
            BigDecimal resultCap,
            BigDecimal processPercent,
            String checkinCountMode) {
        List<String> issues = new ArrayList<>();
        List<SalesPerformanceDay> days = new ArrayList<>();
        if (entry == null || entry.isAfter(month.atEndOfMonth()))
            return new SalesPerformanceResult(
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
            return new SalesPerformanceResult(
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
        if (first && "EXEMPT_ALL".equals(p.firstMonthMode())) {
            BigDecimal process = money(p.bronzeBase().multiply(processPercent).movePointLeft(2));
            BigDecimal collection = money(p.bronzeBase().subtract(process));
            return new SalesPerformanceResult(
                    process,
                    ZERO,
                    process,
                    collection,
                    ZERO,
                    collections,
                    collection,
                    p.bronzeBase(),
                    List.of(),
                    List.of());
        }
        BigDecimal processBudget =
                money(p.monthlyBase().multiply(processPercent).divide(new BigDecimal("100")));
        BigDecimal collectionBudget =
                money(
                        (first ? p.bronzeBase() : p.monthlyBase())
                                .multiply(new BigDecimal("100").subtract(processPercent))
                                .divide(new BigDecimal("100")));
        BigDecimal processDeduction = ZERO;
        if (!linked && processBudget.signum() > 0) issues.add("销售打卡账号未关联员工，过程考核待核对");
        LocalDate from = entry.isAfter(month.atDay(1)) ? entry : month.atDay(1),
                to =
                        leave != null && leave.isBefore(month.atEndOfMonth())
                                ? leave
                                : month.atEndOfMonth();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (processBudget.signum() == 0
                    || assessmentDays != null && !assessmentDays.contains(date)) continue;
            var current = date;
            var evidence = visits.stream().filter(v -> current.equals(v.date())).toList();
            // 打卡可按记录或客户去重；有效拜访始终按当日客户去重，短录音不拼接。
            Set<String> checkins = new HashSet<>(), effective = new HashSet<>();
            // 未通过审核或无有效时长的记录不计达标；只有来源不可用才阻止计算。
            for (var v : evidence) {
                if (!"APPROVED".equals(v.status()) || v.customer() == null) continue;
                if ("RECORD".equals(checkinCountMode) && v.id() == null) continue;
                checkins.add("RECORD".equals(checkinCountMode) ? v.id() : v.customer());
                if (p.dailyVisits() == 0) continue;
                if (!v.durationComplete() || v.durationMillis() == null) continue;
                if (v.durationMillis() >= p.minimumSeconds() * 1000L) effective.add(v.customer());
            }
            int missingCheckins = Math.max(0, p.dailyCheckins() - checkins.size()),
                    missingVisits = Math.max(0, p.dailyVisits() - effective.size());
            BigDecimal deduction =
                    linked
                            ? p.checkinPenalty()
                                    .multiply(BigDecimal.valueOf(missingCheckins))
                                    .add(
                                            p.visitPenalty()
                                                    .multiply(BigDecimal.valueOf(missingVisits)))
                            : null;
            if (linked) processDeduction = processDeduction.add(deduction);
            days.add(
                    new SalesPerformanceDay(
                            date,
                            checkins.size(),
                            effective.size(),
                            missingCheckins,
                            missingVisits,
                            deduction,
                            linked));
        }
        if (assessmentDays != null) processDeduction = processDeduction.min(processBudget);
        BigDecimal processPay =
                issues.isEmpty() ? money(processBudget.subtract(processDeduction).max(ZERO)) : null;
        boolean collectionExempt =
                first && (assessmentDays == null || "EXEMPT_COLLECTION".equals(p.firstMonthMode()));
        BigDecimal target =
                collectionExempt
                        ? ZERO
                        : serviceMonth <= 2 ? p.secondMonthTarget() : p.laterMonthTarget();
        BigDecimal collectionPay;
        if (collectionBudget.signum() == 0) collectionPay = ZERO;
        else if (collectionExempt) collectionPay = collectionBudget;
        else if (collections == null) {
            issues.add("当月回款依据不完整");
            collectionPay = null;
        } else if (target == null || target.signum() <= 0) {
            issues.add("回款目标无效");
            collectionPay = null;
        } else
            collectionPay =
                    money(
                            collectionBudget.multiply(
                                    collections
                                            .max(ZERO)
                                            .divide(target, 12, RoundingMode.HALF_UP)
                                            .min(resultCap)));
        BigDecimal total =
                processPay == null || collectionPay == null
                        ? null
                        : money(processPay.add(collectionPay));
        return new SalesPerformanceResult(
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
