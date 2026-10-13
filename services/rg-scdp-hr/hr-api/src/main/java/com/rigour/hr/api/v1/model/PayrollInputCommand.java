package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollInputCommand(
        Integer revision,
        BigDecimal baseOverride,
        BigDecimal performanceOverride,
        BigDecimal deduction,
        String reason,
        BigDecimal allowanceOverride,
        Long departmentOverrideId) {
    public PayrollInputCommand(
            Integer revision,
            BigDecimal baseOverride,
            BigDecimal performanceOverride,
            BigDecimal deduction,
            String reason,
            BigDecimal allowanceOverride) {
        this(
                revision,
                baseOverride,
                performanceOverride,
                deduction,
                reason,
                allowanceOverride,
                null);
    }

    public PayrollInputCommand(
            Integer revision,
            BigDecimal baseOverride,
            BigDecimal performanceOverride,
            BigDecimal deduction,
            String reason) {
        this(revision, baseOverride, performanceOverride, deduction, reason, null, null);
    }
}
