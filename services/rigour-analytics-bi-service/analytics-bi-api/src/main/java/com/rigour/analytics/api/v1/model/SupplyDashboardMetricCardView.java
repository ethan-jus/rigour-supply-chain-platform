package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 供应链 BI 看板核心指标卡片。 */
public record SupplyDashboardMetricCardView(
        String metricCode,
        String metricName,
        BigDecimal value,
        String unit,
        BigDecimal previousValue,
        BigDecimal changeRate,
        String description) {
}
