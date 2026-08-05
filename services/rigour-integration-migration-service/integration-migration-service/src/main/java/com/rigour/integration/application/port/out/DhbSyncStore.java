package com.rigour.integration.application.port.out;

import com.rigour.integration.application.port.out.DhbClient.Connector;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 订货宝订单同步持久化端口。
 *
 * <p>同步批次、Raw Landing、订单镜像、Outbox 和 checkpoint 必须由同一个 Integration
 * 事务边界维护；这个端口不向 Order Center 或其他 Schema 透传数据库操作。</p>
 */
public interface DhbSyncStore {

    SyncTaskContext loadTask(UUID tenantId, UUID taskId);

    SyncCheckpoint loadCheckpoint(UUID tenantId, UUID taskId);

    SyncRunStarted beginRun(UUID tenantId, UUID actorId, UUID taskId,
                            Instant windowFrom, Instant windowTo);

    PagePersistResult persistOrderPage(UUID tenantId, UUID taskId, UUID runId,
                                      List<OrderSummary> orders, Instant receivedAt);

    void finishRun(UUID tenantId, UUID actorId, UUID taskId, UUID runId,
                   Instant windowFrom, Instant windowTo, String status,
                   long fetchedCount, long acceptedCount, long duplicateCount,
                   long rejectedCount, String cursorAfter,
                   String errorCode, String errorMessage);

    record SyncTaskContext(UUID tenantId, UUID taskId, UUID connectorId,
                           String taskCode, String objectType, String taskStatus,
                           String baseUrl, String secretRef, String connectorStatus,
                           int batchSize, int retryLimit, int overlapSeconds,
                           boolean enabled) {
        public Connector connector() {
            return new Connector(tenantId, connectorId, baseUrl, secretRef);
        }
    }

    record SyncCheckpoint(String cursorType, String cursorValue,
                          Instant sourceUpdatedAt, UUID lastSuccessRunId) {
    }

    record SyncRunStarted(UUID runId, String cursorBefore) {
    }

    record PagePersistResult(long acceptedCount, long duplicateCount,
                             long rejectedCount) {
    }
}
