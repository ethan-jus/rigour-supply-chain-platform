package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 销售、城市、人员等排行榜行。 */
public record SupplyDashboardRankingItemView(
        String rankType,
        String dimensionCode,
        String dimensionName,
        BigDecimal salesAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount,
        Long orderCount,
        Long customerCount,
        BigDecimal rate) {
}
