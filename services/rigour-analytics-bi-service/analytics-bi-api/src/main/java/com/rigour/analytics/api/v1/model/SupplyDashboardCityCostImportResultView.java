package com.rigour.analytics.api.v1.model;

import java.time.Instant;

/** 城市端成本 BI 导入结果。 */
public record SupplyDashboardCityCostImportResultView(
        Integer receivedCount,
        Integer upsertedCount,
        Instant importedAt) {
}
