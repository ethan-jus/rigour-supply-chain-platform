package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollSchemeBaseSalary(
        String mode,
        BigDecimal fixedAmount,
        List<PayrollSalaryTier> tiers,
        String firstMonthTier) {}
