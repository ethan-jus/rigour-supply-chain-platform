package com.rigour.analytics.domain.cost;

import java.time.*;

/** 业务期间由服务端快照截止日计算，避免浏览器时区、闰日及短月造成偏差。 */
public record CostPeriod(
        LocalDate from,
        LocalDate to,
        LocalDate previousFrom,
        LocalDate previousTo,
        String compare) {
    public static CostPeriod resolve(int year, int month, String compare, LocalDate cutoff) {
        if (year < 2000 || year > cutoff.getYear() || month < 0 || month > 12)
            throw new IllegalArgumentException("查询年月超出可用范围");
        if (!"previous".equals(compare) && !"yoy".equals(compare))
            throw new IllegalArgumentException("未知对比方式");
        if (month == 0) {
            LocalDate from = LocalDate.of(year, 1, 1),
                    to = year == cutoff.getYear() ? cutoff : LocalDate.of(year, 12, 31);
            return new CostPeriod(from, to, from.minusYears(1), to.minusYears(1), "yoy");
        }
        YearMonth ym = YearMonth.of(year, month), current = YearMonth.from(cutoff);
        if (ym.isAfter(current)) throw new IllegalArgumentException("不能查询未来月份");
        LocalDate to = ym.equals(current) ? cutoff : ym.atEndOfMonth();
        YearMonth prior = "yoy".equals(compare) ? ym.minusYears(1) : ym.minusMonths(1);
        LocalDate priorTo =
                ym.equals(current)
                        ? prior.atDay(Math.min(to.getDayOfMonth(), prior.lengthOfMonth()))
                        : prior.atEndOfMonth();
        return new CostPeriod(ym.atDay(1), to, prior.atDay(1), priorTo, compare);
    }

    public boolean contains(LocalDate date, boolean previous) {
        return date != null
                && !date.isBefore(previous ? previousFrom : from)
                && !date.isAfter(previous ? previousTo : to);
    }
}
