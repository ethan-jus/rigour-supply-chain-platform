package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollSchemeFactsView(
        int revision,
        BigDecimal performanceBase,
        List<PayrollMetricActual> metrics,
        String reason,
        BigDecimal performanceCompletionPercent,
        BigDecimal commissionAdjustment) {
    public PayrollSchemeFactsView(
            int revision,
            BigDecimal performanceBase,
            List<PayrollMetricActual> metrics,
            String reason,
            BigDecimal performanceCompletionPercent) {
        this(revision, performanceBase, metrics, reason, performanceCompletionPercent, null);
    }

    public PayrollSchemeFactsView(
            int revision,
            BigDecimal performanceBase,
            List<PayrollMetricActual> metrics,
            String reason) {
        this(revision, performanceBase, metrics, reason, null);
    }
}
