package com.rigour.analytics.api.v1.model;

import java.time.Instant;

/** 供应链 BI 单个来源的数据可信度状态。 */
public record SupplyDashboardDataTrustSourceView(
        String sourceCode,
        String sourceName,
        Instant checkpointWatermarkTime,
        Instant lastSuccessTime,
        String checkpointStatus,
        Long lastRunId,
        String lastRunStatus,
        Instant lastRunStartedTime,
        Instant lastRunCompletedTime,
        Long pulledCount,
        Long upsertedCount,
        Long skippedCount,
        Long delayMinutes,
        String delayLevel,
        String failureReason,
        String description) {
}
