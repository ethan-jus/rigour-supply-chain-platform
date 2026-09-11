package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 城市端成本 BI 导入明细。 */
public record SupplyDashboardCityCostImportRecord(
        String regionCode,
        String regionName,
        String costTypeCode,
        String costTypeName,
        Instant costDate,
        BigDecimal costAmount,
        BigDecimal budgetAmount,
        String sourceRecordId,
        String remark) {
}
