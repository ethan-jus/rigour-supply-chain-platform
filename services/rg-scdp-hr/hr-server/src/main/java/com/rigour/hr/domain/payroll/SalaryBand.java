package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;

public record SalaryBand(
        String code,
        BigDecimal lower,
        BigDecimal upper,
        boolean lowerInclusive,
        boolean upperInclusive,
        BigDecimal amount) {}
