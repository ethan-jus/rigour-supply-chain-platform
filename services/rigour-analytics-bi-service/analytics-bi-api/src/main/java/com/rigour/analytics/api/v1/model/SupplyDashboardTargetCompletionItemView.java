package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** BI 城市/销售目标完成度。 */
public record SupplyDashboardTargetCompletionItemView(
        String dimensionType,
        String dimensionCode,
        String dimensionName,
        String metricCode,
        String metricName,
        BigDecimal targetValue,
        BigDecimal actualValue,
        BigDecimal achievementRate) {
}
