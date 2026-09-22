package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.*;
import java.time.Instant;
import java.util.*;

public interface CustomerSyncJobStore {
    CustomerSyncJob reserve(UUID tenant, UUID jobId, UUID connector, Instant now);
    Optional<CustomerSyncJob> find(UUID tenant, UUID jobId);
    boolean claim(UUID tenant, UUID jobId, Instant now);
    void update(UUID tenant, UUID jobId, String status, String stage, SyncResult result, Instant now);
    void heartbeat(UUID tenant, UUID jobId, Instant now);
}
