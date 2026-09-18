package com.rigour.integration.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** 各业务对象独立游标，手动范围拉取不推进自动同步覆盖边界。 */
public interface DhbObjectCheckpointStore {
    Instant successfulTo(UUID tenant, UUID connector, String scope);

    void completed(UUID tenant, UUID connector, String scope, Instant to, UUID runId);
}
