package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollMetricStep(
        BigDecimal lower,
        BigDecimal upper,
        boolean lowerInclusive,
        boolean upperInclusive,
        String mode,
        BigDecimal coefficient) {}
