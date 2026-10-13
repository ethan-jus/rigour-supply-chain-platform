package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollPerformanceDayView(
        LocalDate date,
        int checkins,
        int effectiveVisits,
        int missingCheckins,
        int missingVisits,
        BigDecimal deduction,
        boolean evidenceComplete) {}
