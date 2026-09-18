package com.rigour.analytics.api.v1.model;

import java.time.Instant;

/** 供应链 BI 刷新运行记录视图。 */
public record SupplyDashboardRefreshRunView(
        Long id,
        String jobCode,
        String tenantId,
        String statusCode,
        Instant startedTime,
        Instant completedTime,
        Instant watermarkTime,
        Long pulledCount,
        Long upsertedCount,
        Long skippedCount,
        String failureReason) {
}
