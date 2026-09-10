package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 回款账龄分布项。 */
public record SupplyDashboardPaymentAgingBucketView(
        String bucketCode,
        String bucketName,
        Long orderCount,
        Long customerCount,
        BigDecimal unpaidAmount) {
}
