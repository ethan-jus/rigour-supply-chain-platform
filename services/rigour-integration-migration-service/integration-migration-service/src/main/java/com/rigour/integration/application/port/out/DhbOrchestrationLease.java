package com.rigour.integration.application.port.out;

import java.util.UUID;
import java.util.function.Supplier;

/** Integration统一编排批次的租户连接器级租约。 */
public interface DhbOrchestrationLease {

    <T> T execute(UUID tenantId, UUID connectorId, String ownerId, Supplier<T> action);
}
