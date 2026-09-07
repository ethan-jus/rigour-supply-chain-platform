package com.rigour.analytics.api.v1.model;

import java.time.Instant;

/** 指标口径说明，避免看板使用者误读数据。 */
public record SupplyDashboardMetricDefinitionView(
        String metricCode,
        String metricName,
        String formula,
        String source,
        String owner,
        String version,
        String exclusionRule,
        Instant updatedAt,
        Instant dataCutoffTime) {
}
