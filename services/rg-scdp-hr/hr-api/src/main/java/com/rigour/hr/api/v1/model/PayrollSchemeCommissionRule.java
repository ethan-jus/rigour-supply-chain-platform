package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollSchemeCommissionRule(
        String code,
        String name,
        String recipientRole,
        List<Long> categoryIds,
        List<Long> excludedCategoryIds,
        List<Long> variantIds,
        List<Long> excludedVariantIds,
        String method,
        BigDecimal value,
        String unitCode,
        boolean excluded,
        String rowCode) {
    public PayrollSchemeCommissionRule(
            String code,
            String name,
            String recipientRole,
            java.util.List<Long> categoryIds,
            java.util.List<Long> excludedCategoryIds,
            java.util.List<Long> variantIds,
            java.util.List<Long> excludedVariantIds,
            String method,
            java.math.BigDecimal value,
            String unitCode,
            boolean excluded) {
        this(
                code,
                name,
                recipientRole,
                categoryIds,
                excludedCategoryIds,
                variantIds,
                excludedVariantIds,
                method,
                value,
                unitCode,
                excluded,
                null);
    }
}
