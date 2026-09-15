package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** 销售、城市、人员等排行榜行。 */
public record SupplyDashboardRankingItemView(
        String rankType,
        String dimensionCode,
        String dimensionName,
        String regionCode,
        String regionName,
        BigDecimal salesAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount,
        Long orderCount,
        Long customerCount,
        BigDecimal rate,
        String currentRegionName,
        List<String> orderRegionNames) {
    public SupplyDashboardRankingItemView {
        orderRegionNames = orderRegionNames == null ? List.of() : List.copyOf(orderRegionNames);
    }

    /** 城市、来源及风险排行不携带人员当前归属信息。 */
    public SupplyDashboardRankingItemView(
            String rankType, String dimensionCode, String dimensionName, String regionCode, String regionName,
            BigDecimal salesAmount, BigDecimal paidAmount, BigDecimal unpaidAmount,
            Long orderCount, Long customerCount, BigDecimal rate) {
        this(rankType, dimensionCode, dimensionName, regionCode, regionName, salesAmount, paidAmount,
                unpaidAmount, orderCount, customerCount, rate, null, List.of());
    }
}
