package com.rigour.merchant.api.v1.model;
import java.time.Instant;
import java.util.UUID;

public record CustomerSyncJob(UUID jobId, UUID connectorId, String status, String stage,
        Instant startedAt, Instant heartbeatAt, SyncResult result) {}
