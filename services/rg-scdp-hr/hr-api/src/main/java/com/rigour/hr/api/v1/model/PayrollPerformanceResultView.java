package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollPerformanceResultView(
        BigDecimal processBudget,
        BigDecimal processDeduction,
        BigDecimal processPay,
        BigDecimal collectionBudget,
        BigDecimal collectionTarget,
        BigDecimal collections,
        BigDecimal collectionPay,
        BigDecimal total,
        List<PayrollPerformanceDayView> days,
        List<PayrollCollectionEvidenceView> sources,
        List<String> issues,
        AttendanceAssessmentOverride assessmentOverride) {
    public PayrollPerformanceResultView(
            BigDecimal processBudget,
            BigDecimal processDeduction,
            BigDecimal processPay,
            BigDecimal collectionBudget,
            BigDecimal collectionTarget,
            BigDecimal collections,
            BigDecimal collectionPay,
            BigDecimal total,
            List<PayrollPerformanceDayView> days,
            List<PayrollCollectionEvidenceView> sources,
            List<String> issues) {
        this(
                processBudget,
                processDeduction,
                processPay,
                collectionBudget,
                collectionTarget,
                collections,
                collectionPay,
                total,
                days,
                sources,
                issues,
                null);
    }
}
