package com.rigour.analytics.api.v1.model;

/** 供应链 BI 筛选项。 */
public record SupplyDashboardFilterOptionView(
        String optionType,
        String optionValue,
        String optionLabel,
        Long usageCount,
        String parentOptionValue,
        Long categoryLevel,
        Long ordinal) {
    public SupplyDashboardFilterOptionView(
            String optionType,
            String optionValue,
            String optionLabel,
            Long usageCount) {
        this(optionType, optionValue, optionLabel, usageCount, null, null, null);
    }
}
