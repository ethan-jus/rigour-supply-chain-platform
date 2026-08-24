package com.rigour.integration.application.port.out;

import com.rigour.integration.application.port.out.DhbClient.Connector;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    RawObjectPersistResult persistRawObject(UUID tenantId, UUID connectorId, UUID runId,
                                            String sourceObjectType, String sourceId,
                                            String sourceVersion, Instant sourceUpdatedAt,
                                            Map<String, Object> payload, Instant receivedAt);

    ExternalObjectMapping findActiveMapping(UUID tenantId, UUID connectorId,
                                            String sourceObjectType, String sourceObjectId);

    void upsertExternalObjectMapping(UUID tenantId, UUID actorId,
                                     ExternalObjectMappingWrite value);

    void markRawProcessed(UUID tenantId, UUID rawLandingId);

    void markRawFailed(UUID tenantId, UUID rawLandingId,
                       String errorCode, String errorMessage);

    void recordDeadLetter(UUID tenantId, UUID actorId, DeadLetterWrite value);

    void recordReconciliationCase(UUID tenantId, UUID actorId,
                                  ReconciliationCaseWrite value);

    void recordSyncLog(UUID tenantId, UUID taskId, UUID runId, String level,
                       String message, String errorCode);

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

    record RawObjectPersistResult(UUID rawLandingId, String payloadChecksum,
                                  boolean inserted) {
    }

    record ExternalObjectMapping(UUID id, String sourceObjectType,
                                 String sourceObjectId, String sourceObjectNo,
                                 String internalDomain, String internalObjectType,
                                 Long internalObjectId, String internalObjectNo,
                                 String mappingStatus, String payloadChecksum) {
    }

    record ExternalObjectMappingWrite(UUID connectorId, String sourceObjectType,
                                      String sourceObjectId, String sourceObjectNo,
                                      String internalDomain, String internalObjectType,
                                      Long internalObjectId, String internalObjectNo,
                                      String mappingStatus, UUID lastSeenRunId,
                                      Instant lastSeenAt, String payloadChecksum,
                                      String conflictReason, String remark) {
    }

    record DeadLetterWrite(UUID runId, UUID rawLandingId, String sourceObjectType,
                           String sourceId, String errorCode, String errorMessage) {
    }

    record ReconciliationCaseWrite(UUID runId, String sourceObjectType,
                                   String businessKey, String checkType,
                                   Map<String, Object> expectedValue,
                                   Map<String, Object> actualValue,
                                   String severity, String message) {
    }
}
