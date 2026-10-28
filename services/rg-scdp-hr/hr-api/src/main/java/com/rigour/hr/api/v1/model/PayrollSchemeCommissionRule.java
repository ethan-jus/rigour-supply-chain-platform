package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** 非空 variantIds 将本行限定为指定型号，categoryIds 仅辅助选型；未指定型号时按分类及其子类匹配。 两种范围均应用排除项，型号标准优先于分类标准。 */
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
