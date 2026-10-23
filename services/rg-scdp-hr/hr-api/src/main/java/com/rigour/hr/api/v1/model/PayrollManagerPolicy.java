package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollManagerPolicy(
        List<PayrollMetricDefinition> metrics,
        PayrollProtectionPolicy protection,
        BigDecimal cap,
        List<PayrollMetricStep> steps,
        String calculationMode) {
    public PayrollManagerPolicy {
        // Published historical rules keep their original calculation semantics.
        calculationMode = calculationMode == null ? "TIERED" : calculationMode;
    }

    public PayrollManagerPolicy(
            List<PayrollMetricDefinition> metrics,
            PayrollProtectionPolicy protection,
            BigDecimal cap,
            List<PayrollMetricStep> steps) {
        this(metrics, protection, cap, steps, "TIERED");
    }
}
