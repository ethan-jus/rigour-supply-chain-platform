package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;

public record PerformanceBand(
        BigDecimal lower,
        BigDecimal upper,
        boolean lowerInclusive,
        boolean upperInclusive,
        String mode,
        BigDecimal coefficient) {}
