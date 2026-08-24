package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 订货宝统一同步编排结果。 */
public record DhbSyncOrchestrationResult(
        UUID batchId,
        String status,
        String triggerType,
        Instant startedAt,
        Instant finishedAt,
        List<DhbSyncOrchestrationTenantView> tenants) {
    public DhbSyncOrchestrationResult {
        tenants = tenants == null ? List.of() : List.copyOf(tenants);
    }
}
