package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;

public record SalesPerformancePolicy(
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
