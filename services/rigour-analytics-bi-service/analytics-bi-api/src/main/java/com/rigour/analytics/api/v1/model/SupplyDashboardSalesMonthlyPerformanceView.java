package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 销售人员月度业绩聚合。 */
public record SupplyDashboardSalesMonthlyPerformanceView(
        String period,
        String ownerStaffCode,
        String ownerStaffName,
        String regionCode,
        String regionName,
        BigDecimal salesAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount,
        Long orderCount,
        Long customerCount,
        BigDecimal rate) {
}
