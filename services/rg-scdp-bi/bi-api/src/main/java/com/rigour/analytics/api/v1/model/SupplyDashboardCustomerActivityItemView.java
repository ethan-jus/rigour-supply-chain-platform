package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 客户活跃和流失风险看板项。 */
public record SupplyDashboardCustomerActivityItemView(
        String customerCode,
        String customerName,
        String regionCode,
        String regionName,
        String ownerStaffCode,
        String ownerStaffName,
        String customerTypeCode,
        String customerTypeName,
        String segmentCode,
        String segmentName,
        BigDecimal salesAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount,
        Long orderCount,
        Long paymentCount,
        Instant lastOrderTime,
        Instant lastPaymentTime,
        Long inactiveDays,
        BigDecimal activityScore,
        String churnRiskLevel) {
}
