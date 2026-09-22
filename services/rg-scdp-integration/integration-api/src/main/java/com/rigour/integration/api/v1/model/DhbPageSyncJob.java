package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 页面只提交任务和读取状态，网络超时不是同步任务的失败状态。 */
public record DhbPageSyncJob(UUID jobId, UUID connectorId, String scope, String status,
        String stage, Instant startedAt, Instant heartbeatAt, Instant finishedAt,
        DhbSyncOrchestrationResult result) {}
