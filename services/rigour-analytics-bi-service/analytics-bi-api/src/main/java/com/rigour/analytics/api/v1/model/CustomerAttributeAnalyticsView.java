package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 当前客户来源/经营类别分布及期间订单；缺失属性独立列出，未同步业绩返回 null。 */
public record CustomerAttributeAnalyticsView(String status, Instant syncedAt, Instant from, Instant to,
        List<Item> sources, List<Item> businessCategories) {
    public CustomerAttributeAnalyticsView {
        sources = List.copyOf(sources);
        businessCategories = List.copyOf(businessCategories);
    }

    /** 同一客户在每组维度只计一次，业绩按客户先聚合，避免多订单放大客户数。 */
    public record Item(String name, boolean missing, long customerCount, Long orderingCustomerCount,
            Long orderCount, BigDecimal salesAmount, BigDecimal paidAmount) {}
}
