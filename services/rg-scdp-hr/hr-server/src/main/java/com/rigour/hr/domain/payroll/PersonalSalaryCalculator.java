package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

public final class PersonalSalaryCalculator {
    private PersonalSalaryCalculator() {}

    private static List<LocalDate> dates(
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            Set<LocalDate> workDays,
            String proration) {
        return month.atDay(1)
                .datesUntil(month.atEndOfMonth().plusDays(1))
                .filter(d -> !"WORK_DAYS".equals(proration) || workDays.contains(d))
                .filter(
                        d ->
                                (entry == null || !d.isBefore(entry))
                                        && (leave == null || !d.isAfter(leave)))
                .toList();
    }

    public static BigDecimal average(
            BigDecimal before,
            BigDecimal after,
            LocalDate change,
            LocalDate probationUntil,
            BigDecimal probationPercent,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            Set<LocalDate> workDays,
            String proration) {
        return average(
                before,
                after,
                change,
                probationUntil,
                probationPercent,
                null,
                month,
                entry,
                leave,
                workDays,
                proration);
    }

    public static BigDecimal average(
            BigDecimal before,
            BigDecimal after,
            LocalDate change,
            LocalDate probationUntil,
            BigDecimal probationPercent,
            BigDecimal postProbationPercent,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            Set<LocalDate> workDays,
            String proration) {
        if (before == null) return null;
        var dates = dates(month, entry, leave, workDays, proration);
        if (dates.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (var date : dates) {
            BigDecimal amount = change != null && !date.isBefore(change) ? after : before;
            if (amount == null) return null;
            if (probationUntil != null) {
                boolean probation = date.isBefore(probationUntil);
                BigDecimal percent = probation ? probationPercent : postProbationPercent;
                if (probation && percent == null) return null;
                // Missing values in historical rules retain the former full-base behavior.
                if (percent != null) amount = amount.multiply(percent).movePointLeft(2);
            }
            sum = sum.add(amount);
        }
        return sum.divide(BigDecimal.valueOf(dates.size()), 12, RoundingMode.HALF_UP);
    }

    public static BigDecimal managerAmount(
            BigDecimal before,
            BigDecimal after,
            LocalDate change,
            BigDecimal completion,
            LocalDate protectedUntil,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            Set<LocalDate> workDays,
            String proration) {
        if (before == null || completion == null || protectedUntil == null) return null;
        var dates = dates(month, entry, leave, workDays, proration);
        long denominator =
                "NONE".equals(proration)
                        ? dates.size()
                        : "WORK_DAYS".equals(proration) ? workDays.size() : month.lengthOfMonth();
        if (denominator == 0) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (var date : dates) {
            var base = change != null && !date.isBefore(change) ? after : before;
            if (base == null) return null;
            sum =
                    sum.add(
                            base.multiply(
                                    date.isBefore(protectedUntil) ? BigDecimal.ONE : completion));
        }
        return sum.divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
