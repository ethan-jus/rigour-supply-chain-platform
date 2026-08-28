package com.rigour.analytics.application.model;

import java.time.Instant;

/** 供应链看板查询条件，已经过应用层校验和标准化。 */
public record SupplyDashboardFilter(
        Instant from,
        Instant to,
        String regionCode,
        String ownerStaffCode,
        String customerTypeCode,
        Long productCategoryId,
        String sourceSystemCode) {
}
