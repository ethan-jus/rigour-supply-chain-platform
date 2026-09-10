package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 客户 ABC 分层汇总项。 */
public record SupplyDashboardCustomerSegmentItemView(
        String segmentCode,
        String segmentName,
        Long customerCount,
        BigDecimal salesAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount,
        BigDecimal averageActivityScore,
        Long churnRiskCustomerCount) {
}
