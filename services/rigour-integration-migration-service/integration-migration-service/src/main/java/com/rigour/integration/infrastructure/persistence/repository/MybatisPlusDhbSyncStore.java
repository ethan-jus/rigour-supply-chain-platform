package com.rigour.integration.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.DhbConnectorEntity;
import com.rigour.integration.infrastructure.persistence.entity.ExternalObjectMappingEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationDeadLetterEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationManualResolutionEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationOrderMirrorEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationOutboxEventEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationRawLandingEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationReconciliationCaseEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncCheckpointEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncLogEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncRunEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncTaskEntity;
import com.rigour.integration.infrastructure.persistence.mapper.DhbConnectorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.ExternalObjectMappingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationDeadLetterMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationManualResolutionMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationOrderMirrorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationOutboxEventMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationRawLandingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationReconciliationCaseMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncCheckpointMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncLogMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncRunMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncTaskMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.sync.ExternalSourceCodes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 订货宝订单同步的 MyBatis-Plus 持久化适配器。 */
public class MybatisPlusDhbSyncStore implements DhbSyncStore {
    private static final String SOURCE_SYSTEM = ExternalSourceCodes.INTEGRATION_DHB;
    private static final String SOURCE_OBJECT_TYPE = "ORDER";

    private final DhbConnectorMapper connectorMapper;
    private final IntegrationSyncTaskMapper taskMapper;
    private final IntegrationSyncCheckpointMapper checkpointMapper;
    private final IntegrationSyncRunMapper runMapper;
    private final IntegrationRawLandingMapper rawLandingMapper;
    private final IntegrationOrderMirrorMapper orderMirrorMapper;
    private final IntegrationOutboxEventMapper outboxEventMapper;
    private final ExternalObjectMappingMapper externalObjectMappingMapper;
    private final IntegrationDeadLetterMapper deadLetterMapper;
    private final IntegrationReconciliationCaseMapper reconciliationCaseMapper;
    private final IntegrationManualResolutionMapper manualResolutionMapper;
    private final IntegrationSyncLogMapper syncLogMapper;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public MybatisPlusDhbSyncStore(
            DhbConnectorMapper connectorMapper,
            IntegrationSyncTaskMapper taskMapper,
            IntegrationSyncCheckpointMapper checkpointMapper,
            IntegrationSyncRunMapper runMapper,
            IntegrationRawLandingMapper rawLandingMapper,
            IntegrationOrderMirrorMapper orderMirrorMapper,
            IntegrationOutboxEventMapper outboxEventMapper,
            ExternalObjectMappingMapper externalObjectMappingMapper,
            IntegrationDeadLetterMapper deadLetterMapper,
            IntegrationReconciliationCaseMapper reconciliationCaseMapper,
            IntegrationManualResolutionMapper manualResolutionMapper,
            IntegrationSyncLogMapper syncLogMapper,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.connectorMapper = connectorMapper;
        this.taskMapper = taskMapper;
        this.checkpointMapper = checkpointMapper;
        this.runMapper = runMapper;
        this.rawLandingMapper = rawLandingMapper;
        this.orderMirrorMapper = orderMirrorMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.externalObjectMappingMapper = externalObjectMappingMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.reconciliationCaseMapper = reconciliationCaseMapper;
        this.manualResolutionMapper = manualResolutionMapper;
        this.syncLogMapper = syncLogMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public SyncTaskContext loadTask(UUID tenantId, UUID taskId) {
        return taskQuery(false, tenantId, taskId);
    }

    @Override
    public SyncCheckpoint loadCheckpoint(UUID tenantId, UUID taskId) {
        IntegrationSyncCheckpointEntity row = first(checkpointMapper.selectList(
                Wrappers.<IntegrationSyncCheckpointEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("task_id", bin(taskId))
                        .last("LIMIT 1")));
        if (row == null) return null;
        return new SyncCheckpoint(row.cursorType, row.cursorValue, instant(row.sourceUpdatedAt),
                IntegrationUuidCodec.decode(row.lastSuccessRunId));
    }

    @Override
    public SyncRunStarted beginRun(UUID tenantId, UUID actorId, UUID taskId,
                                   Instant windowFrom, Instant windowTo) {
        return transaction.execute(status -> {
            SyncTaskContext task = taskQuery(true, tenantId, taskId);
            if (task == null) {
                throw new IllegalArgumentException("订货宝同步任务不存在");
            }
            if ("RUNNING".equalsIgnoreCase(task.taskStatus())) {
                throw alreadyRunning();
            }
            if (!task.enabled() || !"ACTIVE".equalsIgnoreCase(task.connectorStatus())) {
                throw new IllegalStateException("订货宝同步任务或连接器未启用");
            }

            String cursorBefore = checkpointValue(tenantId, taskId);
            UUID runId = UUID.randomUUID();
            LocalDateTime now = now();
            IntegrationSyncRunEntity run = new IntegrationSyncRunEntity();
            run.id = bin(runId);
            run.tenantId = bin(tenantId);
            run.taskId = bin(taskId);
            run.triggerType = "MANUAL";
            run.status = "RUNNING";
            run.cursorBefore = cursorBefore;
            run.windowFrom = dateTime(windowFrom);
            run.windowTo = dateTime(windowTo);
            run.startedAt = now;
            run.createdAt = now;
            run.createdBy = bin(actorId);
            run.updatedAt = now;
            run.updatedBy = bin(actorId);
            runMapper.insert(run);

            int changed = taskMapper.update(null, Wrappers.<IntegrationSyncTaskEntity>update()
                    .set("task_status", "RUNNING")
                    .set("last_run_at", now)
                    .set("updated_at", now)
                    .set("updated_by", bin(actorId))
                    .setSql("version=version+1")
                    .eq("tenant_id", bin(tenantId))
                    .eq("id", bin(taskId))
                    .ne("task_status", "RUNNING")
                    .isNull("deleted_at"));
            if (changed != 1) throw alreadyRunning();
            return new SyncRunStarted(runId, cursorBefore);
        });
    }

    @Override
    public PagePersistResult persistOrderPage(UUID tenantId, UUID taskId, UUID runId,
                                              List<OrderSummary> orders, Instant receivedAt) {
        if (orders == null || orders.isEmpty()) {
            return new PagePersistResult(0, 0, 0);
        }
        return transaction.execute(status -> {
            SyncTaskContext task = taskQuery(true, tenantId, taskId);
            if (task == null) {
                throw new IllegalArgumentException("订货宝同步任务不存在");
            }
            UUID connectorId = require(task.connectorId(), "connectorId");
            long accepted = 0;
            long duplicate = 0;
            long rejected = 0;
            Instant received = receivedAt == null ? Instant.now() : receivedAt;
            for (OrderSummary order : orders) {
                if (order == null || blank(order.sourceId()) || blank(order.orderNumber())) {
                    rejected++;
                    continue;
                }
                String payloadJson = writeJson(order.attributes() == null ? Map.of() : order.attributes());
                String payloadChecksum = sha256(payloadJson);
                UUID rawId = UUID.randomUUID();
                IntegrationRawLandingEntity raw = raw(rawId, tenantId, connectorId, runId,
                        SOURCE_OBJECT_TYPE, order.sourceId(), sourceVersion(order),
                        order.updatedAt(), payloadJson, payloadChecksum, received);
                UUID persistedRawId = findRawLanding(tenantId, connectorId,
                        SOURCE_OBJECT_TYPE, order.sourceId(), payloadChecksum);
                boolean inserted = false;
                if (persistedRawId == null) {
                    inserted = insertRaw(raw);
                    persistedRawId = inserted ? rawId : findRawLanding(tenantId, connectorId,
                            SOURCE_OBJECT_TYPE, order.sourceId(), payloadChecksum);
                }
                if (persistedRawId == null) {
                    throw new IllegalStateException("订货宝 Raw Landing 写入后无法回读");
                }
                if (!inserted) {
                    duplicate++;
                    continue;
                }

                UUID mirrorId = upsertOrderMirror(tenantId, connectorId, order, rawId);
                appendOutbox(tenantId, connectorId, mirrorId, rawId, order, payloadChecksum);
                markRawProcessedInternal(tenantId, rawId);
                accepted++;
            }
            return new PagePersistResult(accepted, duplicate, rejected);
        });
    }

    @Override
    public RawObjectPersistResult persistRawObject(UUID tenantId, UUID connectorId, UUID runId,
                                                   String sourceObjectType, String sourceId,
                                                   String sourceVersion, Instant sourceUpdatedAt,
                                                   Map<String, Object> payload, Instant receivedAt) {
        String objectType = required(sourceObjectType, "sourceObjectType");
        String sourceKey = required(sourceId, "sourceId");
        String payloadJson = writeJson(payload == null ? Map.of() : payload);
        String payloadChecksum = sha256(payloadJson);
        UUID rawId = UUID.randomUUID();
        IntegrationRawLandingEntity raw = raw(rawId, tenantId, connectorId, runId,
                objectType, sourceKey, sourceVersion, sourceUpdatedAt, payloadJson,
                payloadChecksum, receivedAt == null ? Instant.now() : receivedAt);
        UUID persistedRawId = findRawLanding(tenantId, connectorId, objectType,
                sourceKey, payloadChecksum);
        boolean inserted = false;
        if (persistedRawId == null) {
            inserted = insertRaw(raw);
            persistedRawId = inserted ? rawId : findRawLanding(tenantId, connectorId, objectType,
                    sourceKey, payloadChecksum);
        }
        if (persistedRawId == null) {
            throw new IllegalStateException("订货宝 Raw Landing 写入后无法回读");
        }
        return new RawObjectPersistResult(persistedRawId, payloadChecksum, inserted);
    }

    @Override
    public ExternalObjectMapping findActiveMapping(UUID tenantId, UUID connectorId,
                                                   String sourceObjectType,
                                                   String sourceObjectId) {
        String objectType = required(sourceObjectType, "sourceObjectType");
        String sourceKey = required(sourceObjectId, "sourceObjectId");
        ExternalObjectMappingEntity row = findActiveMappingRow(tenantId, connectorId,
                objectType, sourceKey);
        if (row == null && connectorId != null) {
            row = findActiveMappingRow(tenantId, null, objectType, sourceKey);
        }
        if (row == null) {
            row = findActiveMappingBySourceNo(tenantId, connectorId, objectType, sourceKey);
        }
        if (row == null && connectorId != null) {
            row = findActiveMappingBySourceNo(tenantId, null, objectType, sourceKey);
        }
        return row == null ? null : mapping(row);
    }

    @Override
    public List<TransferInboundReceiptCandidate> findTransferInboundReceiptCandidates(
            UUID tenantId, UUID connectorId, int limit) {
        int queryLimit = limit < 1 ? 500 : Math.min(limit, 5000);
        QueryWrapper<IntegrationRawLandingEntity> query = Wrappers.<IntegrationRawLandingEntity>query()
                .select("id", "source_id", "source_updated_at", "payload_json", "received_at")
                .eq("tenant_id", bin(tenantId))
                .eq(connectorId != null, "connector_id", bin(connectorId))
                .isNull(connectorId == null, "connector_id")
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", "WAREHOUSING_RECEIPT")
                .orderByDesc("received_at")
                .last("LIMIT " + queryLimit);
        return rawLandingMapper.selectList(query).stream()
                .map(row -> new TransferInboundReceiptCandidate(
                        IntegrationUuidCodec.decode(row.id),
                        row.sourceId,
                        firstNonBlank(sourceNo(readJsonMap(row.payloadJson)), row.sourceId),
                        instant(row.sourceUpdatedAt),
                        instant(row.receivedAt),
                        readJsonMap(row.payloadJson)))
                .toList();
    }

    @Override
    public ManualResolution findActiveManualResolution(UUID tenantId, UUID connectorId,
                                                       String resolutionType,
                                                       String sourceObjectType,
                                                       String sourceId) {
        String type = required(resolutionType, "resolutionType").toUpperCase(Locale.ROOT);
        String objectType = required(sourceObjectType, "sourceObjectType").toUpperCase(Locale.ROOT);
        String sourceKey = required(sourceId, "sourceId");
        UUID scopedConnectorId = require(connectorId, "connectorId");
        IntegrationManualResolutionEntity row = first(manualResolutionMapper.selectList(
                Wrappers.<IntegrationManualResolutionEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("connector_id", bin(scopedConnectorId))
                        .eq("resolution_type", type)
                        .eq("source_object_type", objectType)
                        .eq("source_id", sourceKey)
                        .eq("status", "ACTIVE")
                        .orderByDesc("updated_at", "created_at")
                        .last("LIMIT 1")));
        if (row == null) return null;
        return new ManualResolution(IntegrationUuidCodec.decode(row.id), row.resolutionType,
                row.sourceObjectType, row.sourceId, row.selectedSourceObjectType,
                row.selectedSourceId, row.selectedInternalObjectType,
                row.selectedInternalObjectId, readJsonMap(row.evidenceJson), row.reason);
    }

    @Override
    public void upsertExternalObjectMapping(UUID tenantId, UUID actorId,
                                            ExternalObjectMappingWrite value) {
        if (value == null) throw new IllegalArgumentException("external object mapping is required");
        String objectType = required(value.sourceObjectType(), "sourceObjectType");
        String sourceKey = required(value.sourceObjectId(), "sourceObjectId");
        String mappingStatus = normalizeMappingStatus(value.mappingStatus());
        ExternalObjectMappingEntity existing = findMappingRow(tenantId, value.connectorId(),
                objectType, sourceKey, false);
        if (existing == null) {
            existing = findMappingRow(tenantId, value.connectorId(), objectType, sourceKey, true);
        }
        if (existing == null) {
            ExternalObjectMappingEntity row = new ExternalObjectMappingEntity();
            row.id = bin(UUID.randomUUID());
            row.tenantId = bin(tenantId);
            applyMapping(row, value, objectType, sourceKey, mappingStatus);
            row.sourceSystem = SOURCE_SYSTEM;
            row.version = 0L;
            row.createdAt = now();
            row.createdBy = bin(actorId);
            row.updatedAt = row.createdAt;
            row.updatedBy = bin(actorId);
            try {
                externalObjectMappingMapper.insert(row);
                return;
            } catch (DuplicateKeyException ignored) {
                existing = findMappingRow(tenantId, value.connectorId(), objectType, sourceKey, true);
            }
        }
        if (existing == null) {
            throw new IllegalStateException("外部对象映射写入冲突后无法回读");
        }
        applyMapping(existing, value, objectType, sourceKey, mappingStatus);
        existing.sourceSystem = SOURCE_SYSTEM;
        existing.version = existing.version == null ? 1L : existing.version + 1;
        existing.updatedAt = now();
        existing.updatedBy = bin(actorId);
        existing.deletedAt = null;
        existing.deletedBy = null;
        existing.deleteReason = null;
        externalObjectMappingMapper.updateById(existing);
    }

    @Override
    public void markRawProcessed(UUID tenantId, UUID rawLandingId) {
        markRawProcessedInternal(tenantId, rawLandingId);
    }

    @Override
    public void markRawFailed(UUID tenantId, UUID rawLandingId,
                              String errorCode, String errorMessage) {
        rawLandingMapper.update(null, Wrappers.<IntegrationRawLandingEntity>update()
                .set("landing_status", "FAILED")
                .set("error_code", blankToNull(errorCode))
                .set("error_message", truncate(errorMessage))
                .set("last_attempt_at", now())
                .setSql("attempts=attempts+1")
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(rawLandingId)));
    }

    @Override
    public void recordDeadLetter(UUID tenantId, UUID actorId, DeadLetterWrite value) {
        if (value == null) throw new IllegalArgumentException("dead letter is required");
        LocalDateTime now = now();
        byte[] tenant = bin(require(tenantId, "tenantId"));
        byte[] actor = bin(actorId);
        String objectType = required(value.sourceObjectType(), "sourceObjectType");
        String businessKey = required(value.sourceId(), "sourceId");
        resolveOpenDeadLetters(tenant, actor, objectType, businessKey, now);
        IntegrationDeadLetterEntity row = new IntegrationDeadLetterEntity();
        row.id = bin(UUID.randomUUID());
        row.tenantId = tenant;
        row.runId = bin(value.runId());
        row.rawLandingId = bin(value.rawLandingId());
        row.sourceSystem = SOURCE_SYSTEM;
        row.sourceObjectType = objectType;
        row.sourceId = businessKey;
        row.status = "OPEN";
        row.attempts = 1;
        row.lastErrorCode = blankToNull(value.errorCode());
        row.lastErrorMessage = truncate(value.errorMessage());
        row.version = 0L;
        row.createdAt = now;
        row.createdBy = actor;
        row.updatedAt = now;
        row.updatedBy = actor;
        deadLetterMapper.insert(row);
    }

    @Override
    public void recordReconciliationCase(UUID tenantId, UUID actorId,
                                         ReconciliationCaseWrite value) {
        if (value == null) throw new IllegalArgumentException("reconciliation case is required");
        LocalDateTime now = now();
        byte[] tenant = bin(require(tenantId, "tenantId"));
        byte[] actor = bin(actorId);
        String objectType = required(value.sourceObjectType(), "sourceObjectType");
        String businessKey = required(value.businessKey(), "businessKey");
        resolveOpenReconciliationCases(tenant, actor, objectType, businessKey, now);
        IntegrationReconciliationCaseEntity row = new IntegrationReconciliationCaseEntity();
        row.id = bin(UUID.randomUUID());
        row.tenantId = tenant;
        row.runId = bin(value.runId());
        row.sourceSystem = SOURCE_SYSTEM;
        row.sourceObjectType = objectType;
        row.businessKey = businessKey;
        row.checkType = required(value.checkType(), "checkType");
        row.expectedValueJson = writeJson(value.expectedValue() == null ? Map.of() : value.expectedValue());
        row.actualValueJson = writeJson(value.actualValue() == null ? Map.of() : value.actualValue());
        row.status = "OPEN";
        row.severity = normalizeSeverity(value.severity());
        row.message = truncate(required(value.message(), "message"));
        row.version = 0L;
        row.createdAt = now;
        row.createdBy = actor;
        row.updatedAt = now;
        row.updatedBy = actor;
        reconciliationCaseMapper.insert(row);
    }

    @Override
    public void resolveProjectionIssues(UUID tenantId, UUID actorId,
                                        String sourceObjectType, String sourceId) {
        if (blank(sourceObjectType) || blank(sourceId)) return;
        LocalDateTime current = now();
        byte[] tenant = bin(require(tenantId, "tenantId"));
        byte[] actor = bin(actorId);
        String objectType = sourceObjectType.strip();
        String businessKey = sourceId.strip();
        deadLetterMapper.update(null, Wrappers.<IntegrationDeadLetterEntity>update()
                .set("status", "RESOLVED")
                .set("resolved_at", current)
                .set("resolved_by", actor)
                .set("updated_at", current)
                .set("updated_by", actor)
                .setSql("version=version+1")
                .eq("tenant_id", tenant)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", objectType)
                .eq("source_id", businessKey)
                .in("status", "OPEN", "REPLAYING"));
        reconciliationCaseMapper.update(null, Wrappers.<IntegrationReconciliationCaseEntity>update()
                .set("status", "RESOLVED")
                .set("resolved_at", current)
                .set("resolved_by", actor)
                .set("updated_at", current)
                .set("updated_by", actor)
                .setSql("version=version+1")
                .eq("tenant_id", tenant)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", objectType)
                .eq("business_key", businessKey)
                .in("status", "OPEN", "ACKNOWLEDGED"));
    }

    @Override
    public void resolveRecoveredProjectionIssues(UUID tenantId, UUID actorId) {
        LocalDateTime current = now();
        byte[] tenant = bin(require(tenantId, "tenantId"));
        byte[] actor = bin(actorId);
        deadLetterMapper.update(null, Wrappers.<IntegrationDeadLetterEntity>update()
                .set("status", "RESOLVED")
                .set("resolved_at", current)
                .set("resolved_by", actor)
                .set("updated_at", current)
                .set("updated_by", actor)
                .setSql("version=version+1")
                .eq("tenant_id", tenant)
                .eq("source_system", SOURCE_SYSTEM)
                .in("status", "OPEN", "REPLAYING")
                .exists("""
                        select 1
                        from integration_external_object_mapping m
                        where m.tenant_id = integration_dead_letter.tenant_id
                          and m.source_system = integration_dead_letter.source_system
                          and m.source_object_type = integration_dead_letter.source_object_type
                          and (m.source_object_id = integration_dead_letter.source_id
                               or m.source_object_no = integration_dead_letter.source_id)
                          and m.mapping_status = 'ACTIVE'
                          and m.deleted_at is null
                          and m.internal_object_id is not null
                          and m.last_seen_at is not null
                          and m.last_seen_at > integration_dead_letter.updated_at
                        """));
        reconciliationCaseMapper.update(null, Wrappers.<IntegrationReconciliationCaseEntity>update()
                .set("status", "RESOLVED")
                .set("resolved_at", current)
                .set("resolved_by", actor)
                .set("updated_at", current)
                .set("updated_by", actor)
                .setSql("version=version+1")
                .eq("tenant_id", tenant)
                .eq("source_system", SOURCE_SYSTEM)
                .in("status", "OPEN", "ACKNOWLEDGED")
                .exists("""
                        select 1
                        from integration_external_object_mapping m
                        where m.tenant_id = integration_reconciliation_case.tenant_id
                          and m.source_system = integration_reconciliation_case.source_system
                          and m.source_object_type = integration_reconciliation_case.source_object_type
                          and (m.source_object_id = integration_reconciliation_case.business_key
                               or m.source_object_no = integration_reconciliation_case.business_key)
                          and m.mapping_status = 'ACTIVE'
                          and m.deleted_at is null
                          and m.internal_object_id is not null
                          and m.last_seen_at is not null
                          and m.last_seen_at > integration_reconciliation_case.updated_at
                        """));
    }

    private void resolveOpenDeadLetters(byte[] tenant, byte[] actor,
                                        String sourceObjectType, String sourceId,
                                        LocalDateTime resolvedAt) {
        deadLetterMapper.update(null, Wrappers.<IntegrationDeadLetterEntity>update()
                .set("status", "RESOLVED")
                .set("resolved_at", resolvedAt)
                .set("resolved_by", actor)
                .set("updated_at", resolvedAt)
                .set("updated_by", actor)
                .setSql("version=version+1")
                .eq("tenant_id", tenant)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceObjectType)
                .eq("source_id", sourceId)
                .in("status", "OPEN", "REPLAYING"));
    }

    private void resolveOpenReconciliationCases(byte[] tenant, byte[] actor,
                                                String sourceObjectType, String businessKey,
                                                LocalDateTime resolvedAt) {
        reconciliationCaseMapper.update(null, Wrappers.<IntegrationReconciliationCaseEntity>update()
                .set("status", "RESOLVED")
                .set("resolved_at", resolvedAt)
                .set("resolved_by", actor)
                .set("updated_at", resolvedAt)
                .set("updated_by", actor)
                .setSql("version=version+1")
                .eq("tenant_id", tenant)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceObjectType)
                .eq("business_key", businessKey)
                .in("status", "OPEN", "ACKNOWLEDGED"));
    }

    @Override
    public void recordSyncLog(UUID tenantId, UUID taskId, UUID runId, String level,
                              String message, String errorCode) {
        IntegrationSyncLogEntity row = new IntegrationSyncLogEntity();
        row.id = bin(UUID.randomUUID());
        row.tenantId = bin(tenantId);
        row.taskId = bin(taskId);
        row.runId = bin(runId);
        row.logLevel = normalizeLogLevel(level);
        row.message = truncate(required(message, "message"));
        row.errorCode = blankToNull(errorCode);
        row.occurredAt = now();
        syncLogMapper.insert(row);
    }

    @Override
    public void finishRun(UUID tenantId, UUID actorId, UUID taskId, UUID runId,
                          Instant windowFrom, Instant windowTo, String status,
                          long fetchedCount, long acceptedCount, long duplicateCount,
                          long rejectedCount, String cursorAfter,
                          String errorCode, String errorMessage) {
        String normalizedStatus = normalizeRunStatus(status);
        transaction.executeWithoutResult(transactionStatus -> {
            LocalDateTime now = now();
            int changed = runMapper.update(null, Wrappers.<IntegrationSyncRunEntity>update()
                    .set("status", normalizedStatus)
                    .set("cursor_after", "SUCCEEDED".equals(normalizedStatus) ? cursorAfter : null)
                    .set("window_from", dateTime(windowFrom))
                    .set("window_to", dateTime(windowTo))
                    .set("fetched_count", fetchedCount)
                    .set("accepted_count", acceptedCount)
                    .set("duplicate_count", duplicateCount)
                    .set("rejected_count", rejectedCount)
                    .set("finished_at", now)
                    .set("error_code", blankToNull(errorCode))
                    .set("error_message", truncate(errorMessage))
                    .set("updated_at", now)
                    .set("updated_by", bin(actorId))
                    .setSql("version=version+1")
                    .eq("tenant_id", bin(tenantId))
                    .eq("id", bin(runId))
                    .eq("task_id", bin(taskId)));
            requireChanged(changed);

            String taskStatus = "SUCCEEDED".equals(normalizedStatus) ? "COMPLETED" : "FAILED";
            taskMapper.update(null, Wrappers.<IntegrationSyncTaskEntity>update()
                    .set("task_status", taskStatus)
                    .set("next_run_at", null)
                    .set("updated_at", now)
                    .set("updated_by", bin(actorId))
                    .setSql("version=version+1")
                    .eq("tenant_id", bin(tenantId))
                    .eq("id", bin(taskId))
                    .isNull("deleted_at"));

            if ("SUCCEEDED".equals(normalizedStatus) && windowTo != null) {
                upsertCheckpoint(tenantId, taskId, runId, windowTo, cursorAfter, actorId);
            }
        });
    }

    private SyncTaskContext taskQuery(boolean forUpdate, UUID tenantId, UUID taskId) {
        QueryWrapper<IntegrationSyncTaskEntity> query = Wrappers.<IntegrationSyncTaskEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(taskId))
                .isNull("deleted_at")
                .last(forUpdate ? "FOR UPDATE" : "LIMIT 1");
        IntegrationSyncTaskEntity task = first(taskMapper.selectList(query));
        if (task == null) return null;
        DhbConnectorEntity connector = first(connectorMapper.selectList(
                Wrappers.<DhbConnectorEntity>query()
                        .eq("tenant_id", task.tenantId)
                        .eq("id", task.connectorId)
                        .isNull("deleted_at")
                        .last("LIMIT 1")));
        if (connector == null) return null;
        return new SyncTaskContext(IntegrationUuidCodec.decode(task.tenantId),
                IntegrationUuidCodec.decode(task.id), IntegrationUuidCodec.decode(task.connectorId),
                task.taskCode, task.objectType, task.taskStatus, connector.baseUrl,
                connector.authSecretRef, connector.status, defaultInt(task.batchSize, 100),
                defaultInt(task.retryLimit, 3), defaultInt(task.overlapSeconds, 300),
                task.enabled == null || task.enabled == 1);
    }

    private String checkpointValue(UUID tenantId, UUID taskId) {
        IntegrationSyncCheckpointEntity row = first(checkpointMapper.selectList(
                Wrappers.<IntegrationSyncCheckpointEntity>query()
                        .select("cursor_value")
                        .eq("tenant_id", bin(tenantId))
                        .eq("task_id", bin(taskId))
                        .last("LIMIT 1")));
        return row == null ? null : row.cursorValue;
    }

    private UUID upsertOrderMirror(UUID tenantId, UUID connectorId,
                                   OrderSummary order, UUID rawId) {
        IntegrationOrderMirrorEntity existing = findOrderMirrorRow(tenantId, connectorId, order.sourceId());
        if (existing == null) {
            UUID id = UUID.randomUUID();
            IntegrationOrderMirrorEntity row = new IntegrationOrderMirrorEntity();
            row.id = bin(id);
            row.tenantId = bin(tenantId);
            row.connectorId = bin(connectorId);
            applyOrderMirror(row, order, rawId);
            row.version = 0L;
            row.createdAt = now();
            row.updatedAt = row.createdAt;
            try {
                orderMirrorMapper.insert(row);
                return id;
            } catch (DuplicateKeyException ignored) {
                existing = findOrderMirrorRow(tenantId, connectorId, order.sourceId());
            }
        }
        if (existing == null) {
            throw new IllegalStateException("订货宝订单镜像写入冲突后无法回读");
        }
        applyOrderMirror(existing, order, rawId);
        existing.version = existing.version == null ? 1L : existing.version + 1;
        existing.updatedAt = now();
        orderMirrorMapper.updateById(existing);
        return IntegrationUuidCodec.decode(existing.id);
    }

    private void appendOutbox(UUID tenantId, UUID connectorId, UUID mirrorId, UUID rawId,
                              OrderSummary order, String sourceChecksum) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceSystem", SOURCE_SYSTEM);
        payload.put("connectorId", connectorId.toString());
        payload.put("sourceOrderId", order.sourceId());
        payload.put("orderNo", order.orderNumber());
        payload.put("sourceStatus", order.status());
        payload.put("amount", order.amount());
        payload.put("createdAt", string(order.createdAt()));
        payload.put("updatedAt", string(order.updatedAt()));
        payload.put("customerNumber", order.customerNumber());
        payload.put("paymentStatus", order.paymentStatus());
        payload.put("rawLandingId", rawId.toString());
        payload.put("sourcePayloadChecksum", sourceChecksum);
        payload.put("attributes", order.attributes());
        String payloadJson = writeJson(payload);
        IntegrationOutboxEventEntity row = new IntegrationOutboxEventEntity();
        row.id = bin(UUID.randomUUID());
        row.tenantId = bin(tenantId);
        row.aggregateType = "ORDER";
        row.aggregateId = bin(mirrorId);
        row.eventType = "DHB_ORDER_MIRROR_UPSERTED";
        row.eventKey = "DHB_ORDER_MIRROR:" + connectorId + ":" + order.sourceId()
                + ":" + sourceChecksum;
        row.payloadJson = payloadJson;
        row.payloadChecksum = sha256(payloadJson);
        row.status = "PENDING";
        row.attempts = 0;
        row.availableAt = now();
        row.createdAt = row.availableAt;
        row.updatedAt = row.availableAt;
        try {
            outboxEventMapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // 同一来源payload只需要一个出站事件。
        }
    }

    private void upsertCheckpoint(UUID tenantId, UUID taskId, UUID runId,
                                  Instant windowTo, String cursorAfter, UUID actorId) {
        IntegrationSyncCheckpointEntity existing = first(checkpointMapper.selectList(
                Wrappers.<IntegrationSyncCheckpointEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("task_id", bin(taskId))
                        .last("LIMIT 1")));
        if (existing == null) {
            IntegrationSyncCheckpointEntity row = new IntegrationSyncCheckpointEntity();
            row.id = bin(UUID.randomUUID());
            row.tenantId = bin(tenantId);
            row.taskId = bin(taskId);
            row.cursorType = "TIME_WINDOW";
            row.cursorValue = cursorAfter;
            row.sourceUpdatedAt = dateTime(windowTo);
            row.lastSuccessRunId = bin(runId);
            row.version = 0L;
            row.createdAt = now();
            row.createdBy = bin(actorId);
            row.updatedAt = row.createdAt;
            row.updatedBy = bin(actorId);
            try {
                checkpointMapper.insert(row);
                return;
            } catch (DuplicateKeyException ignored) {
                existing = first(checkpointMapper.selectList(
                        Wrappers.<IntegrationSyncCheckpointEntity>query()
                                .eq("tenant_id", bin(tenantId))
                                .eq("task_id", bin(taskId))
                                .last("LIMIT 1")));
            }
        }
        if (existing == null) {
            throw new IllegalStateException("订货宝同步游标写入冲突后无法回读");
        }
        existing.cursorType = "TIME_WINDOW";
        existing.cursorValue = cursorAfter;
        existing.sourceUpdatedAt = dateTime(windowTo);
        existing.lastSuccessRunId = bin(runId);
        existing.version = existing.version == null ? 1L : existing.version + 1;
        existing.updatedAt = now();
        existing.updatedBy = bin(actorId);
        checkpointMapper.updateById(existing);
    }

    private IntegrationOrderMirrorEntity findOrderMirrorRow(UUID tenantId, UUID connectorId,
                                                            String sourceOrderId) {
        return first(orderMirrorMapper.selectList(Wrappers.<IntegrationOrderMirrorEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("connector_id", bin(connectorId))
                .eq("source_order_id", sourceOrderId)
                .last("LIMIT 1")));
    }

    private UUID findRawLanding(UUID tenantId, UUID connectorId, String sourceObjectType,
                                String sourceId, String payloadChecksum) {
        QueryWrapper<IntegrationRawLandingEntity> query =
                Wrappers.<IntegrationRawLandingEntity>query()
                        .select("id")
                        .eq("tenant_id", bin(tenantId))
                        .eq(connectorId != null, "connector_id", bin(connectorId))
                        .isNull(connectorId == null, "connector_id")
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", sourceObjectType)
                        .eq("source_id", sourceId)
                        .eq("payload_checksum", payloadChecksum)
                        .last("LIMIT 1");
        IntegrationRawLandingEntity row = first(rawLandingMapper.selectList(query));
        return row == null ? null : IntegrationUuidCodec.decode(row.id);
    }

    private ExternalObjectMappingEntity findActiveMappingRow(UUID tenantId, UUID connectorId,
                                                             String sourceObjectType,
                                                             String sourceObjectId) {
        QueryWrapper<ExternalObjectMappingEntity> query = mappingQuery(tenantId, connectorId,
                sourceObjectType, sourceObjectId)
                .eq("mapping_status", "ACTIVE")
                .isNull("deleted_at")
                .orderByDesc("updated_at")
                .last("LIMIT 1");
        return first(externalObjectMappingMapper.selectList(query));
    }

    private ExternalObjectMappingEntity findActiveMappingBySourceNo(UUID tenantId, UUID connectorId,
                                                                    String sourceObjectType,
                                                                    String sourceObjectNo) {
        QueryWrapper<ExternalObjectMappingEntity> query = Wrappers.<ExternalObjectMappingEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq(connectorId != null, "connector_id", bin(connectorId))
                .isNull(connectorId == null, "connector_id")
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceObjectType)
                .eq("source_object_no", sourceObjectNo)
                .eq("mapping_status", "ACTIVE")
                .isNull("deleted_at")
                .orderByDesc("updated_at")
                .last("LIMIT 1");
        return first(externalObjectMappingMapper.selectList(query));
    }

    private ExternalObjectMappingEntity findMappingRow(UUID tenantId, UUID connectorId,
                                                       String sourceObjectType,
                                                       String sourceObjectId,
                                                       boolean includeDeleted) {
        QueryWrapper<ExternalObjectMappingEntity> query = mappingQuery(tenantId, connectorId,
                sourceObjectType, sourceObjectId);
        if (!includeDeleted) query.isNull("deleted_at");
        return first(externalObjectMappingMapper.selectList(query.orderByDesc("updated_at")
                .last("LIMIT 1")));
    }

    private QueryWrapper<ExternalObjectMappingEntity> mappingQuery(
            UUID tenantId, UUID connectorId, String sourceObjectType, String sourceObjectId) {
        QueryWrapper<ExternalObjectMappingEntity> query =
                Wrappers.<ExternalObjectMappingEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq(connectorId != null, "connector_id", bin(connectorId))
                        .isNull(connectorId == null, "connector_id")
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", sourceObjectType)
                        .eq("source_object_id", sourceObjectId);
        return query;
    }

    private boolean insertRaw(IntegrationRawLandingEntity raw) {
        try {
            rawLandingMapper.insert(raw);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    private IntegrationRawLandingEntity raw(UUID rawId, UUID tenantId, UUID connectorId,
                                            UUID runId, String sourceObjectType,
                                            String sourceId, String sourceVersion,
                                            Instant sourceUpdatedAt, String payloadJson,
                                            String payloadChecksum, Instant receivedAt) {
        IntegrationRawLandingEntity row = new IntegrationRawLandingEntity();
        row.id = bin(rawId);
        row.tenantId = bin(tenantId);
        row.connectorId = bin(connectorId);
        row.runId = bin(runId);
        row.sourceSystem = SOURCE_SYSTEM;
        row.sourceObjectType = sourceObjectType;
        row.sourceId = sourceId;
        row.sourceVersion = sourceVersion;
        row.sourceUpdatedAt = dateTime(sourceUpdatedAt);
        row.payloadJson = payloadJson;
        row.payloadChecksum = payloadChecksum;
        row.receivedAt = dateTime(receivedAt);
        row.landingStatus = "RECEIVED";
        row.attempts = 0;
        row.version = 0L;
        return row;
    }

    private void applyMapping(ExternalObjectMappingEntity row, ExternalObjectMappingWrite value,
                              String sourceObjectType, String sourceObjectId,
                              String mappingStatus) {
        row.connectorId = bin(value.connectorId());
        row.sourceObjectType = sourceObjectType;
        row.sourceObjectId = sourceObjectId;
        row.sourceObjectNo = blankToNull(value.sourceObjectNo());
        row.internalDomain = blankToNull(value.internalDomain());
        row.internalObjectType = blankToNull(value.internalObjectType());
        row.internalObjectId = value.internalObjectId();
        row.internalObjectNo = blankToNull(value.internalObjectNo());
        row.mappingStatus = mappingStatus;
        row.lastSeenRunId = bin(value.lastSeenRunId());
        row.lastSeenAt = dateTime(value.lastSeenAt() == null ? Instant.now() : value.lastSeenAt());
        row.sourceDeletedAt = null;
        row.payloadChecksum = blankToNull(value.payloadChecksum());
        row.conflictReason = truncate(value.conflictReason());
        row.remark = truncate(value.remark());
    }

    private void applyOrderMirror(IntegrationOrderMirrorEntity row, OrderSummary order, UUID rawId) {
        row.sourceOrderId = required(order.sourceId(), "sourceOrderId");
        row.orderNo = required(order.orderNumber(), "orderNumber");
        row.sourceStatus = order.status();
        row.amount = order.amount() == null ? BigDecimal.ZERO : order.amount();
        row.orderTime = dateTime(order.createdAt());
        row.rawLandingId = bin(rawId);
        row.mirrorStatus = "ACTIVE";
    }

    private ExternalObjectMapping mapping(ExternalObjectMappingEntity row) {
        return new ExternalObjectMapping(IntegrationUuidCodec.decode(row.id),
                row.sourceObjectType, row.sourceObjectId, row.sourceObjectNo,
                row.internalDomain, row.internalObjectType, row.internalObjectId,
                row.internalObjectNo, row.mappingStatus, row.payloadChecksum);
    }

    private void markRawProcessedInternal(UUID tenantId, UUID rawLandingId) {
        rawLandingMapper.update(null, Wrappers.<IntegrationRawLandingEntity>update()
                .set("landing_status", "PROCESSED")
                .set("processed_at", now())
                .set("error_code", null)
                .set("error_message", null)
                .set("last_attempt_at", now())
                .setSql("attempts=attempts+1")
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(rawLandingId)));
    }

    private static <T> T first(List<T> rows) {
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private static String sourceVersion(OrderSummary order) {
        return order.updatedAt() == null ? null : order.updatedAt().toString();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException error) {
            throw new IllegalStateException("订货宝同步 payload 序列化失败", error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String value) {
        if (blank(value)) return Map.of();
        try {
            Object parsed = objectMapper.readValue(value, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, item) -> {
                    if (key != null) result.put(String.valueOf(key), item);
                });
                return result;
            }
            return Map.of();
        } catch (RuntimeException error) {
            throw new IllegalStateException("订货宝 Raw Landing payload 解析失败", error);
        }
    }

    private static String sourceNo(Map<String, Object> payload) {
        return firstNonBlank(text(payload.get("warehousing_num")),
                text(payload.get("WarehousingNum")),
                text(payload.get("storage_num")),
                text(payload.get("StorageNum")),
                text(payload.get("receipt_no")),
                text(payload.get("ReceiptNo")));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private static String normalizeRunStatus(String status) {
        String value = required(status, "status").toUpperCase();
        if (!Set.of("SUCCEEDED", "PARTIAL", "FAILED").contains(value)) {
            throw new IllegalArgumentException("Invalid sync run status");
        }
        return value;
    }

    private static String normalizeMappingStatus(String status) {
        String value = required(status, "mappingStatus").toUpperCase();
        if (!Set.of("ACTIVE", "REMOVED", "CONFLICT", "IGNORED").contains(value)) {
            throw new IllegalArgumentException("Invalid external mapping status");
        }
        return value;
    }

    private static String normalizeSeverity(String severity) {
        String value = blank(severity) ? "WARN" : severity.strip().toUpperCase();
        if (!Set.of("INFO", "WARN", "ERROR").contains(value)) {
            throw new IllegalArgumentException("Invalid reconciliation severity");
        }
        return value;
    }

    private static String normalizeLogLevel(String level) {
        String value = blank(level) ? "INFO" : level.strip().toUpperCase();
        if (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(value)) {
            throw new IllegalArgumentException("Invalid sync log level");
        }
        return value;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.strip();
    }

    private static String string(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) return value.strip();
        }
        return null;
    }

    private static byte[] bin(UUID value) {
        return IntegrationUuidCodec.encode(value);
    }

    private static UUID require(UUID value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static LocalDateTime dateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static void requireChanged(int changed) {
        if (changed != 1) throw new IllegalStateException("同步批次状态已被其他执行者改变");
    }

    private static BusinessException alreadyRunning() {
        return new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                "相同租户和订货宝任务已有同步批次运行中", java.util.List.of());
    }
}
