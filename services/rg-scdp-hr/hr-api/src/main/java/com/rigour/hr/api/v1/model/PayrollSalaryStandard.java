package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

/** Employee-specific pay amounts. Allowances belong to separate department rules. */
public record PayrollSalaryStandard(
        PayrollSchemeBaseSalary baseSalary,
        BigDecimal performanceBase,
        PayrollProrationPolicy proration,
        PayrollPersonalPolicy personalPolicy) {
    public PayrollSalaryStandard(
            PayrollSchemeBaseSalary baseSalary,
            BigDecimal performanceBase,
            PayrollProrationPolicy proration) {
        this(baseSalary, performanceBase, proration, null);
    }
}
