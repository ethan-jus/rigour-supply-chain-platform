package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 同一 BI 快照内相邻等长订单期间的精确比较，不表示历史到账快照。 */
public record BiComparisonView(
        Instant from, Instant to, Instant previousFrom, Instant previousTo, Instant generatedAt,
        Values current, Values previous, List<City> cities) {
    public record Values(BigDecimal salesAmount, BigDecimal paidAmount, BigDecimal unpaidAmount,
                         long orderCount, long customerCount) { }
    public record City(String regionCode, String regionName, Values current, Values previous) { }
}
