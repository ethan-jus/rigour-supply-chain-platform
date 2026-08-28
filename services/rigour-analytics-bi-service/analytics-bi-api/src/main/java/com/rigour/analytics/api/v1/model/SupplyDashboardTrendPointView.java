package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 看板趋势点。 */
public record SupplyDashboardTrendPointView(
        String metricCode,
        String period,
        BigDecimal value,
        BigDecimal secondaryValue) {
}
