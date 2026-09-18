package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 城市分类销售额；分类编码为分类 ID 字符串，缺失或无效 ID 使用 UNKNOWN；金额为原始行金额合计。 */
public record SupplyDashboardCityProductItemView(
        String regionCode,
        String regionName,
        String categoryCode,
        String categoryName,
        BigDecimal salesAmount,
        Long orderCount,
        Long customerCount) {
}
