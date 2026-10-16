package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollCalculationView(
        BigDecimal baseSalary,
        BigDecimal performance,
        BigDecimal commission,
        BigDecimal heldCommission,
        BigDecimal allowance,
        BigDecimal deduction,
        BigDecimal grossPay,
        BigDecimal netPay,
        List<String> issues,
        List<PayrollCommissionDetailView> details,
        List<PayrollRuleView> rules,
        PayrollInputCommand input,
        String sourceFingerprint,
        PayrollPerformanceResultView performanceDetail,
        PayrollSalarySourceView salarySource,
        BigDecimal correctionAmount,
        List<PayrollCorrectionView> corrections,
        PayrollSchemeSnapshotView scheme) {
    public PayrollCalculationView(
            BigDecimal baseSalary,
            BigDecimal performance,
            BigDecimal commission,
            BigDecimal heldCommission,
            BigDecimal allowance,
            BigDecimal deduction,
            BigDecimal grossPay,
            BigDecimal netPay,
            List<String> issues,
            List<PayrollCommissionDetailView> details,
            List<PayrollRuleView> rules,
            PayrollInputCommand input,
            String sourceFingerprint,
            PayrollPerformanceResultView performanceDetail,
            PayrollSalarySourceView salarySource,
            BigDecimal correctionAmount,
            List<PayrollCorrectionView> corrections) {
        this(
                baseSalary,
                performance,
                commission,
                heldCommission,
                allowance,
                deduction,
                grossPay,
                netPay,
                issues,
                details,
                rules,
                input,
                sourceFingerprint,
                performanceDetail,
                salarySource,
                correctionAmount,
                corrections,
                null);
    }

    public PayrollCalculationView(
            BigDecimal baseSalary,
            BigDecimal performance,
            BigDecimal commission,
            BigDecimal heldCommission,
            BigDecimal allowance,
            BigDecimal deduction,
            BigDecimal grossPay,
            BigDecimal netPay,
            List<String> issues,
            List<PayrollCommissionDetailView> details,
            List<PayrollRuleView> rules,
            PayrollInputCommand input,
            String sourceFingerprint,
            PayrollPerformanceResultView performanceDetail,
            PayrollSalarySourceView salarySource) {
        this(
                baseSalary,
                performance,
                commission,
                heldCommission,
                allowance,
                deduction,
                grossPay,
                netPay,
                issues,
                details,
                rules,
                input,
                sourceFingerprint,
                performanceDetail,
                salarySource,
                BigDecimal.ZERO,
                List.of());
    }
}
