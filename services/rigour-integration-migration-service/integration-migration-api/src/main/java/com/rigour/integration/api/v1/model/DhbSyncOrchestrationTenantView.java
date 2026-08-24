package com.rigour.integration.api.v1.model;

import java.util.List;
import java.util.UUID;

/** 订货宝统一同步中某个租户连接器的执行结果。 */
public record DhbSyncOrchestrationTenantView(
        UUID tenantId,
        UUID connectorId,
        String status,
        List<DhbSyncOrchestrationStepView> steps) {
    public DhbSyncOrchestrationTenantView {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
