package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 供应链风险提示行。 */
public record SupplyDashboardRiskItemView(
        String riskType,
        String riskLevel,
        String dimensionCode,
        String dimensionName,
        String description,
        BigDecimal primaryValue,
        BigDecimal secondaryValue,
        Instant observedAt) {
}
