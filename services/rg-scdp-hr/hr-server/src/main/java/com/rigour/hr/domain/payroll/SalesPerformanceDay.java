package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalesPerformanceDay(
        LocalDate date,
        int checkins,
        int effectiveVisits,
        int missingCheckins,
        int missingVisits,
        BigDecimal deduction,
        boolean evidenceComplete) {}
