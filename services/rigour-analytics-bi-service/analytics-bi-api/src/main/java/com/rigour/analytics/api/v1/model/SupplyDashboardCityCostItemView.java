package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 城市端成本看板行。 */
public record SupplyDashboardCityCostItemView(
        String regionCode,
        String regionName,
        BigDecimal costAmount,
        BigDecimal budgetAmount,
        BigDecimal varianceAmount,
        BigDecimal salesAmount,
        BigDecimal costRate,
        Long recordCount,
        Instant latestCostTime) {
}
