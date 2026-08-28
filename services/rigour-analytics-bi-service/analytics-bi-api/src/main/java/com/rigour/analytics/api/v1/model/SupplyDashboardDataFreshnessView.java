package com.rigour.analytics.api.v1.model;

import java.time.Instant;

/** 看板数据源新鲜度。 */
public record SupplyDashboardDataFreshnessView(
        String sourceCode,
        String sourceName,
        Instant latestUpdatedTime,
        String status,
        String description) {
}
