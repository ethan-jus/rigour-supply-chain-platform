package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollCommissionTraceView(
        String orderId,
        String cityCode,
        Long variantId,
        BigDecimal baseAmount,
        BigDecimal rate,
        String method,
        LocalDate fullyPaidDate,
        BigDecimal refundedAmount,
        String ruleCode,
        String schemeVersionId,
        String originalLineId,
        PayrollProductSnapshotView product,
        PayrollAppliedRuleView appliedRule,
        LocalDate orderDate,
        PayrollOrderSettlementView settlement,
        BigDecimal collectionAmount) {
    public PayrollCommissionTraceView(
            String orderId,
            String cityCode,
            Long variantId,
            BigDecimal baseAmount,
            BigDecimal rate,
            String method,
            LocalDate fullyPaidDate,
            BigDecimal refundedAmount,
            String ruleCode,
            String schemeVersionId,
            String originalLineId,
            PayrollProductSnapshotView product,
            PayrollAppliedRuleView appliedRule,
            LocalDate orderDate,
            PayrollOrderSettlementView settlement) {
        this(
                orderId,
                cityCode,
                variantId,
                baseAmount,
                rate,
                method,
                fullyPaidDate,
                refundedAmount,
                ruleCode,
                schemeVersionId,
                originalLineId,
                product,
                appliedRule,
                orderDate,
                settlement,
                null);
    }

    public PayrollCommissionTraceView(
            String orderId,
            String cityCode,
            Long variantId,
            BigDecimal baseAmount,
            BigDecimal rate,
            String method,
            LocalDate fullyPaidDate,
            BigDecimal refundedAmount,
            String ruleCode,
            String schemeVersionId,
            String originalLineId,
            PayrollProductSnapshotView product,
            PayrollAppliedRuleView appliedRule,
            LocalDate orderDate) {
        this(
                orderId,
                cityCode,
                variantId,
                baseAmount,
                rate,
                method,
                fullyPaidDate,
                refundedAmount,
                ruleCode,
                schemeVersionId,
                originalLineId,
                product,
                appliedRule,
                orderDate,
                null);
    }

    public PayrollCommissionTraceView(
            String orderId,
            String cityCode,
            Long variantId,
            BigDecimal baseAmount,
            BigDecimal rate,
            String method,
            LocalDate fullyPaidDate,
            BigDecimal refundedAmount,
            String ruleCode,
            String schemeVersionId,
            String originalLineId) {
        this(
                orderId,
                cityCode,
                variantId,
                baseAmount,
                rate,
                method,
                fullyPaidDate,
                refundedAmount,
                ruleCode,
                schemeVersionId,
                originalLineId,
                null,
                null,
                null);
    }
}
