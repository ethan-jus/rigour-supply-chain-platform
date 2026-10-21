package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollSchemeSnapshotView(
        PayrollSchemeVersionView scheme,
        String salaryTier,
        BigDecimal achievement,
        BigDecimal performanceBase,
        List<PayrollMetricResultView> metrics,
        BigDecimal managerCoefficient,
        BigDecimal managerCompletion,
        int protectedDays,
        int assessedDays,
        List<String> explanations,
        List<PayrollAppliedRuleView> appliedRules) {
    public PayrollSchemeSnapshotView {
        appliedRules = appliedRules == null ? List.of() : List.copyOf(appliedRules);
    }

    public PayrollSchemeSnapshotView(
            PayrollSchemeVersionView scheme,
            String salaryTier,
            BigDecimal achievement,
            BigDecimal performanceBase,
            List<PayrollMetricResultView> metrics,
            BigDecimal managerCoefficient,
            BigDecimal managerCompletion,
            int protectedDays,
            int assessedDays,
            List<String> explanations) {
        this(
                scheme,
                salaryTier,
                achievement,
                performanceBase,
                metrics,
                managerCoefficient,
                managerCompletion,
                protectedDays,
                assessedDays,
                explanations,
                List.of());
    }
}
