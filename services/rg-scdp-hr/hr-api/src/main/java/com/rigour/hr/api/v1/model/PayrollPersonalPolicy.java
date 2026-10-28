package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Versioned personal exceptions. End dates below are exclusive; changes start on the stated date.
 */
public record PayrollPersonalPolicy(
        List<LocalDate> exemptDays,
        String allowanceVersionId,
        LocalDate probationUntil,
        BigDecimal probationBasePercent,
        LocalDate protectionUntil,
        LocalDate salaryChangeDate,
        BigDecimal baseAfterChange,
        BigDecimal performanceAfterChange,
        BigDecimal postProbationBasePercent) {
    public PayrollPersonalPolicy(
            List<LocalDate> exemptDays,
            String allowanceVersionId,
            LocalDate probationUntil,
            BigDecimal probationBasePercent,
            LocalDate protectionUntil,
            LocalDate salaryChangeDate,
            BigDecimal baseAfterChange,
            BigDecimal performanceAfterChange) {
        this(
                exemptDays,
                allowanceVersionId,
                probationUntil,
                probationBasePercent,
                protectionUntil,
                salaryChangeDate,
                baseAfterChange,
                performanceAfterChange,
                null);
    }

    public PayrollPersonalPolicy {
        exemptDays = exemptDays == null ? List.of() : List.copyOf(exemptDays);
    }
}
