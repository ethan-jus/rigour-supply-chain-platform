package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollSalaryTier(
        String code,
        String name,
        BigDecimal lower,
        BigDecimal upper,
        boolean lowerInclusive,
        boolean upperInclusive,
        BigDecimal amount,
        BigDecimal performanceBase) {}
