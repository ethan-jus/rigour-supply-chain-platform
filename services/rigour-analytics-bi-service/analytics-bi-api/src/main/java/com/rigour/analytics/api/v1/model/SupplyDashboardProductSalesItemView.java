package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 供应链 BI 商品/分类销售排行项。 */
public record SupplyDashboardProductSalesItemView(
        String rankType,
        String dimensionCode,
        String dimensionName,
        String categoryCode,
        String categoryName,
        BigDecimal salesQuantity,
        BigDecimal salesAmount,
        BigDecimal discountAmount,
        BigDecimal refundAmount,
        BigDecimal salesNetAmount,
        BigDecimal estimatedCostAmount,
        BigDecimal estimatedGrossProfit,
        BigDecimal estimatedGrossProfitRate,
        BigDecimal costCoverageRate,
        Long orderCount,
        Long customerCount) {
}
