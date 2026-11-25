package com.rigour.integration.application.port.out;

import com.rigour.integration.api.v1.model.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DhbPageSyncJobStore {
    DhbPageSyncJob reserve(UUID tenant, UUID jobId, DhbPageSyncCommand command, Instant now);

    Optional<DhbPageSyncJob> find(UUID tenant, UUID jobId);

    Optional<DhbPageSyncJob> latest(UUID tenant, UUID connector, String scope);

    boolean claim(UUID tenant, UUID jobId, Instant now);

    void progress(UUID tenant, UUID jobId, String stage, Instant now);

    void heartbeat(UUID tenant, UUID jobId, Instant now);

    default void dependency(UUID tenant, UUID jobId, UUID dependencyId) {}

    /** 仅在心跳版本未变且两层执行租约均已失效时收敛孤立任务。 */
    boolean finishAbandoned(UUID tenant, DhbPageSyncJob observed, Instant now);

    void finish(
            UUID tenant,
            UUID jobId,
            String status,
            String stage,
            DhbSyncOrchestrationResult result,
            Instant now);
}
