package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 城市分类销售额；分类编码为分类 ID 字符串，金额为订单行 line_amount 合计，不聚合异单位数量。 */
public record SupplyDashboardCityProductItemView(
        String regionCode,
        String regionName,
        String categoryCode,
        String categoryName,
        BigDecimal salesAmount,
        Long orderCount,
        Long customerCount) {
}
