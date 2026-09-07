package com.rigour.integration.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingView;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderMirrorView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncLogView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.api.v1.model.DhbExternalObjectMappingPageView;
import com.rigour.integration.api.v1.model.DhbExternalObjectMappingView;
import com.rigour.integration.api.v1.model.DhbManualResolutionCommand;
import com.rigour.integration.api.v1.model.DhbManualResolutionView;
import com.rigour.integration.api.v1.model.DhbSyncExceptionView;
import com.rigour.integration.api.v1.model.DhbSyncLogDetailView;
import com.rigour.integration.api.v1.model.DhbSyncReconciliationCaseView;
import com.rigour.integration.api.v1.model.DhbSyncRunAuditView;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.DhbConnectorEntity;
import com.rigour.integration.infrastructure.persistence.entity.ExternalObjectMappingEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationDeadLetterEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationFieldMappingEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationManualResolutionEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationOrderMirrorEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationRawLandingEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationReconciliationCaseEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncLogEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncRunEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncTaskEntity;
import com.rigour.integration.infrastructure.persistence.mapper.DhbConnectorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.ExternalObjectMappingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationDeadLetterMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFieldMappingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationManualResolutionMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationOrderMirrorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationRawLandingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationReconciliationCaseMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncLogMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncRunMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncTaskMapper;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.sync.ExternalSourceCodes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/** 订货宝同步控制面 MyBatis-Plus 仓储；所有查询绑定租户边界。 */
public class MybatisPlusDhbIntegrationStore implements DhbIntegrationStore {
    private static final String DEFAULT_ORDER_TASK_CODE = "DHB_ORDER_DEFAULT";
    private static final String DEFAULT_PRODUCT_MASTER_TASK_CODE = "DHB_PRODUCT_MASTER_DEFAULT";
    private static final String DEFAULT_SUPPLY_CHAIN_TASK_CODE = "DHB_SUPPLY_CHAIN_DEFAULT";
    private static final String DEFAULT_CRM_MASTER_TASK_CODE = "DHB_CRM_MASTER_DEFAULT";
    private static final String DEFAULT_BUSINESS_DICTIONARY_TASK_CODE = "DHB_BUSINESS_DICTIONARY_DEFAULT";
    private static final Set<String> CONNECTOR_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> TASK_STATUSES = Set.of("IDLE", "RUNNING", "PAUSED", "FAILED", "COMPLETED");
    private static final Set<String> TRANSFORM_TYPES = Set.of("DIRECT", "CONSTANT", "EXPRESSION", "DICTIONARY");
    private static final Set<String> MAPPING_STATUSES = Set.of("ACTIVE", "REMOVED", "CONFLICT", "IGNORED");
    private static final Set<String> MANUAL_RESOLUTION_TYPES = Set.of("TRANSFER_INBOUND_RECEIPT");
    private static final Set<String> MANUAL_RESOLUTION_STATUSES = Set.of("ACTIVE", "SUPERSEDED", "CANCELLED");
    private static final String DEFAULT_SOURCE_SYSTEM = ExternalSourceCodes.INTEGRATION_DHB;

    private final DhbConnectorMapper connectorMapper;
    private final IntegrationSyncTaskMapper taskMapper;
    private final IntegrationFieldMappingMapper fieldMappingMapper;
    private final IntegrationOrderMirrorMapper orderMirrorMapper;
    private final IntegrationSyncLogMapper syncLogMapper;
    private final ExternalObjectMappingMapper externalObjectMappingMapper;
    private final IntegrationSyncRunMapper syncRunMapper;
    private final IntegrationDeadLetterMapper deadLetterMapper;
    private final IntegrationReconciliationCaseMapper reconciliationCaseMapper;
    private final IntegrationManualResolutionMapper manualResolutionMapper;
    private final IntegrationRawLandingMapper rawLandingMapper;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public MybatisPlusDhbIntegrationStore(
            DhbConnectorMapper connectorMapper,
            IntegrationSyncTaskMapper taskMapper,
            IntegrationFieldMappingMapper fieldMappingMapper,
            IntegrationOrderMirrorMapper orderMirrorMapper,
            IntegrationSyncLogMapper syncLogMapper,
            ExternalObjectMappingMapper externalObjectMappingMapper,
            IntegrationSyncRunMapper syncRunMapper,
            IntegrationDeadLetterMapper deadLetterMapper,
            IntegrationReconciliationCaseMapper reconciliationCaseMapper,
            IntegrationManualResolutionMapper manualResolutionMapper,
            IntegrationRawLandingMapper rawLandingMapper,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.connectorMapper = connectorMapper;
        this.taskMapper = taskMapper;
        this.fieldMappingMapper = fieldMappingMapper;
        this.orderMirrorMapper = orderMirrorMapper;
        this.syncLogMapper = syncLogMapper;
        this.externalObjectMappingMapper = externalObjectMappingMapper;
        this.syncRunMapper = syncRunMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.reconciliationCaseMapper = reconciliationCaseMapper;
        this.manualResolutionMapper = manualResolutionMapper;
        this.rawLandingMapper = rawLandingMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ConnectorView> connectors(UUID tenantId) {
        return connectorMapper.selectList(Wrappers.<DhbConnectorEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .isNull("deleted_at")
                        .orderByAsc("connector_code"))
                .stream().map(MybatisPlusDhbIntegrationStore::connector).toList();
    }

    @Override
    public ConnectorView connector(UUID tenantId, UUID connectorId) {
        DhbConnectorEntity row = connectorRow(tenantId, connectorId);
        if (row == null) throw new AuthorizationDeniedException("integration:dhb:connector");
        return connector(row);
    }

    @Override
    public void recordConnectionTest(UUID tenantId, UUID actorId, UUID connectorId,
                                     ConnectionTestResult result) {
        if (connectorId == null || result == null) {
            throw new IllegalArgumentException("connectorId and result are required");
        }
        String credentialStatus = result.success() ? "VALID"
                : ("DHB_AUTH_FAILED".equals(result.code()) ? "INVALID" : "UNKNOWN");
        int changed = connectorMapper.update(null, Wrappers.<DhbConnectorEntity>update()
                .set("credential_status", credentialStatus)
                .set("last_checked_at", now())
                .set("last_error_code", result.success() ? null : result.code())
                .set("last_error_message", result.success() ? null : truncate(result.message()))
                .set("updated_at", now())
                .set("updated_by", bin(actorId))
                .setSql("version=version+1")
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(connectorId))
                .isNull("deleted_at"));
        requireChanged(changed);
    }

    @Override
    public ConnectorView createConnector(UUID tenantId, UUID actorId, ConnectorCommand command) {
        validateConnector(command);
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = now();
            DhbConnectorEntity row = new DhbConnectorEntity();
            row.id = bin(id);
            row.tenantId = bin(tenantId);
            row.connectorCode = normalizedCode(command.code());
            row.connectorName = required(command.name(), "name");
            row.baseUrl = blankToNull(command.baseUrl());
            row.authSecretRef = blankToNull(command.authSecretRef());
            row.status = allowed(command.status(), CONNECTOR_STATUSES, "status");
            row.version = 0L;
            row.createdAt = now;
            row.createdBy = bin(actorId);
            row.updatedAt = now;
            row.updatedBy = bin(actorId);
            connectorMapper.insert(row);
            ensureDefaultSyncTasks(tenantId, actorId, id);
        });
        return connectorById(tenantId, id);
    }

    @Override
    public ConnectorView updateConnector(UUID tenantId, UUID actorId, UUID id, ConnectorCommand command) {
        validateConnector(command);
        String connectorStatus = allowed(command.status(), CONNECTOR_STATUSES, "status");
        transaction.executeWithoutResult(status -> {
            int changed = connectorMapper.update(null, Wrappers.<DhbConnectorEntity>update()
                    .set("connector_name", required(command.name(), "name"))
                    .set("base_url", blankToNull(command.baseUrl()))
                    .set("auth_secret_ref", blankToNull(command.authSecretRef()))
                    .set("status", connectorStatus)
                    .set("updated_at", now())
                    .set("updated_by", bin(actorId))
                    .setSql("version=version+1")
                    .eq("tenant_id", bin(tenantId))
                    .eq("id", bin(id))
                    .eq("connector_code", normalizedCode(command.code()))
                    .eq("version", command.version())
                    .isNull("deleted_at"));
            requireChanged(changed);
            if ("ACTIVE".equals(connectorStatus)) ensureDefaultSyncTasks(tenantId, actorId, id);
        });
        return connectorById(tenantId, id);
    }

    @Override
    public List<SyncTaskView> syncTasks(UUID tenantId) {
        return taskMapper.selectList(Wrappers.<IntegrationSyncTaskEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .isNull("deleted_at")
                        .orderByAsc("task_code"))
                .stream().map(MybatisPlusDhbIntegrationStore::syncTask).toList();
    }

    @Override
    public List<SyncTargetView> activeSyncTargets(String objectType) {
        String normalizedType = normalizedObjectType(objectType);
        List<SyncTargetView> targets = new ArrayList<>();
        List<IntegrationSyncTaskEntity> tasks = taskMapper.selectList(
                Wrappers.<IntegrationSyncTaskEntity>query()
                        .eq("object_type", normalizedType)
                        .eq("enabled", 1)
                        .ne("task_status", "PAUSED")
                        .isNull("deleted_at")
                        .orderByAsc("tenant_id", "id"));
        for (IntegrationSyncTaskEntity task : tasks) {
            DhbConnectorEntity connector = connectorRow(
                    IntegrationUuidCodec.decode(task.tenantId),
                    IntegrationUuidCodec.decode(task.connectorId));
            if (connector != null && "ACTIVE".equals(connector.status)) {
                targets.add(new SyncTargetView(IntegrationUuidCodec.decode(task.id),
                        IntegrationUuidCodec.decode(task.tenantId),
                        IntegrationUuidCodec.decode(task.connectorId)));
            }
        }
        return targets;
    }

    @Override
    public SyncTaskView createSyncTask(UUID tenantId, UUID actorId, SyncTaskCommand command) {
        validateSyncTask(command);
        requireConnector(tenantId, command.connectorId());
        String objectType = normalizedObjectType(command.objectType());
        if ("ORDER".equals(objectType)) {
            requireNoExistingOrderSyncTask(tenantId, command.connectorId(), null);
        }
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = now();
            IntegrationSyncTaskEntity row = new IntegrationSyncTaskEntity();
            row.id = bin(id);
            row.tenantId = bin(tenantId);
            row.connectorId = bin(command.connectorId());
            row.taskCode = normalizedCode(command.code());
            row.objectType = objectType;
            row.taskStatus = allowed(command.status(), TASK_STATUSES, "status");
            row.nextRunAt = dateTime(command.nextRunAt());
            row.version = 0L;
            row.createdAt = now;
            row.createdBy = bin(actorId);
            row.updatedAt = now;
            row.updatedBy = bin(actorId);
            taskMapper.insert(row);
        });
        return syncTaskById(tenantId, id);
    }

    @Override
    public SyncTaskView updateSyncTask(UUID tenantId, UUID actorId, UUID id, SyncTaskCommand command) {
        validateSyncTask(command);
        requireConnector(tenantId, command.connectorId());
        String taskCode = normalizedCode(command.code());
        String objectType = normalizedObjectType(command.objectType());
        validateDefaultTaskType(taskCode, objectType);
        if ("ORDER".equals(objectType)) {
            requireNoExistingOrderSyncTask(tenantId, command.connectorId(), id);
        }
        transaction.executeWithoutResult(status -> {
            int changed = taskMapper.update(null, Wrappers.<IntegrationSyncTaskEntity>update()
                    .set("connector_id", bin(command.connectorId()))
                    .set("object_type", objectType)
                    .set("task_status", allowed(command.status(), TASK_STATUSES, "status"))
                    .set("next_run_at", dateTime(command.nextRunAt()))
                    .set("updated_at", now())
                    .set("updated_by", bin(actorId))
                    .setSql("version=version+1")
                    .eq("tenant_id", bin(tenantId))
                    .eq("id", bin(id))
                    .eq("task_code", taskCode)
                    .eq("version", command.version())
                    .isNull("deleted_at"));
            requireChanged(changed);
        });
        return syncTaskById(tenantId, id);
    }

    @Override
    public List<OrderMirrorView> orderMirrors(UUID tenantId, int limit, int offset) {
        return orderMirrorMapper.selectList(Wrappers.<IntegrationOrderMirrorEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .orderByDesc("order_time", "created_at")
                        .last("LIMIT " + safeLimit(limit) + " OFFSET " + safeOffset(offset)))
                .stream().map(MybatisPlusDhbIntegrationStore::orderMirror).toList();
    }

    @Override
    public List<SyncLogView> syncLogs(UUID tenantId, int limit) {
        return syncLogMapper.selectList(Wrappers.<IntegrationSyncLogEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .orderByDesc("occurred_at")
                        .last("LIMIT " + safeLimit(limit)))
                .stream().map(MybatisPlusDhbIntegrationStore::syncLog).toList();
    }

    @Override
    public List<FieldMappingView> fieldMappings(UUID tenantId, UUID connectorId) {
        requireConnector(tenantId, connectorId);
        return fieldMappingMapper.selectList(Wrappers.<IntegrationFieldMappingEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("connector_id", bin(connectorId))
                        .isNull("deleted_at")
                        .orderByAsc("source_field"))
                .stream().map(MybatisPlusDhbIntegrationStore::fieldMapping).toList();
    }

    @Override
    public FieldMappingView saveFieldMapping(UUID tenantId, UUID actorId, UUID id,
                                             FieldMappingCommand command) {
        validateFieldMapping(command);
        requireConnector(tenantId, command.connectorId());
        UUID targetId = id == null ? UUID.randomUUID() : id;
        transaction.executeWithoutResult(status -> {
            if (id == null) {
                LocalDateTime now = now();
                IntegrationFieldMappingEntity row = new IntegrationFieldMappingEntity();
                row.id = bin(targetId);
                row.tenantId = bin(tenantId);
                row.connectorId = bin(command.connectorId());
                row.sourceField = normalizedCode(command.sourceField());
                row.targetField = normalizedCode(command.targetField());
                row.transformType = allowed(command.transformType(), TRANSFORM_TYPES, "transformType");
                row.enabled = command.enabled() ? 1 : 0;
                row.version = 0L;
                row.createdAt = now;
                row.createdBy = bin(actorId);
                row.updatedAt = now;
                row.updatedBy = bin(actorId);
                fieldMappingMapper.insert(row);
            } else {
                int changed = fieldMappingMapper.update(null, Wrappers.<IntegrationFieldMappingEntity>update()
                        .set("target_field", normalizedCode(command.targetField()))
                        .set("transform_type", allowed(command.transformType(), TRANSFORM_TYPES, "transformType"))
                        .set("enabled", command.enabled() ? 1 : 0)
                        .set("updated_at", now())
                        .set("updated_by", bin(actorId))
                        .setSql("version=version+1")
                        .eq("tenant_id", bin(tenantId))
                        .eq("id", bin(id))
                        .eq("connector_id", bin(command.connectorId()))
                        .eq("source_field", normalizedCode(command.sourceField()))
                        .eq("version", command.version())
                        .isNull("deleted_at"));
                requireChanged(changed);
            }
        });
        return fieldMappingById(tenantId, targetId);
    }

    @Override
    public int saveExternalObjectMappings(UUID tenantId, UUID actorId,
                                          List<ExternalObjectMappingCommand> commands) {
        if (commands == null || commands.isEmpty()) return 0;
        Map<ExternalMappingKey, ExternalObjectMappingEntity> existingRows =
                externalObjectMappingRows(tenantId, commands);
        Integer accepted = transaction.execute(status -> {
            int count = 0;
            for (ExternalObjectMappingCommand command : commands) {
                saveExternalObjectMapping(tenantId, actorId, command, existingRows);
                count++;
            }
            return count;
        });
        return accepted == null ? 0 : accepted;
    }

    @Override
    public DhbExternalObjectMappingPageView externalObjectMappings(
            UUID tenantId, String sourceObjectType, String internalDomain, String mappingStatus,
            int limit, int offset) {
        QueryWrapper<ExternalObjectMappingEntity> countQuery =
                externalMappingQuery(tenantId, sourceObjectType, internalDomain, mappingStatus);
        long total = externalObjectMappingMapper.selectCount(countQuery);
        QueryWrapper<ExternalObjectMappingEntity> listQuery =
                externalMappingQuery(tenantId, sourceObjectType, internalDomain, mappingStatus)
                        .orderByDesc("updated_at", "created_at")
                        .last("LIMIT " + safeLimit(limit) + " OFFSET " + safeOffset(offset));
        List<DhbExternalObjectMappingView> items = externalObjectMappingMapper.selectList(listQuery)
                .stream().map(MybatisPlusDhbIntegrationStore::externalObjectMapping).toList();
        return new DhbExternalObjectMappingPageView(total, items);
    }

    @Override
    public List<DhbSyncRunAuditView> syncRuns(UUID tenantId, String objectType, String status, int limit) {
        String normalizedType = optionalCode(objectType);
        String normalizedStatus = optionalCode(status);
        List<DhbSyncRunAuditView> result = new ArrayList<>();
        QueryWrapper<IntegrationSyncRunEntity> query = Wrappers.<IntegrationSyncRunEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq(normalizedStatus != null, "status", normalizedStatus)
                .orderByDesc("created_at")
                .last("LIMIT " + Math.min(500, safeLimit(limit) * 10));
        for (IntegrationSyncRunEntity run : syncRunMapper.selectList(query)) {
            IntegrationSyncTaskEntity task = taskById(tenantId, IntegrationUuidCodec.decode(run.taskId));
            if (task == null) continue;
            if (normalizedType != null && !normalizedType.equals(task.objectType)) continue;
            result.add(syncRunAudit(run, task));
            if (result.size() >= safeLimit(limit)) break;
        }
        return result;
    }

    @Override
    public List<DhbSyncLogDetailView> syncLogDetails(UUID tenantId, UUID runId, String level, int limit) {
        String normalizedLevel = optionalCode(level);
        QueryWrapper<IntegrationSyncLogEntity> query = Wrappers.<IntegrationSyncLogEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq(runId != null, "run_id", bin(runId))
                .eq(normalizedLevel != null, "log_level", normalizedLevel)
                .orderByDesc("occurred_at")
                .last("LIMIT " + safeLimit(limit));
        List<DhbSyncLogDetailView> rows = new ArrayList<>();
        for (IntegrationSyncLogEntity log : syncLogMapper.selectList(query)) {
            IntegrationSyncTaskEntity task = taskById(tenantId, IntegrationUuidCodec.decode(log.taskId));
            rows.add(syncLogDetail(log, task));
        }
        return rows;
    }

    @Override
    public List<DhbSyncExceptionView> syncExceptions(UUID tenantId, String status, int limit) {
        String normalizedStatus = optionalCode(status);
        return deadLetterMapper.selectList(Wrappers.<IntegrationDeadLetterEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq(normalizedStatus != null, "status", normalizedStatus)
                        .orderByDesc("updated_at", "created_at")
                        .last("LIMIT " + safeLimit(limit)))
                .stream().map(MybatisPlusDhbIntegrationStore::syncException).toList();
    }

    @Override
    public List<DhbSyncReconciliationCaseView> syncReconciliationCases(
            UUID tenantId, String status, String severity, int limit) {
        String normalizedStatus = optionalCode(status);
        String normalizedSeverity = optionalCode(severity);
        return reconciliationCaseMapper.selectList(Wrappers.<IntegrationReconciliationCaseEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq(normalizedStatus != null, "status", normalizedStatus)
                        .eq(normalizedSeverity != null, "severity", normalizedSeverity)
                        .orderByDesc("created_at")
                        .last("LIMIT " + safeLimit(limit)))
                .stream().map(MybatisPlusDhbIntegrationStore::reconciliationCase).toList();
    }

    @Override
    public List<DhbManualResolutionView> manualResolutions(
            UUID tenantId, String resolutionType, String sourceObjectType,
            String sourceId, String status, int limit) {
        String normalizedResolutionType = optionalCode(resolutionType);
        String normalizedSourceObjectType = optionalCode(sourceObjectType);
        String normalizedStatus = status == null || status.isBlank()
                ? null
                : allowed(status, MANUAL_RESOLUTION_STATUSES, "status");
        String sourceKey = blankToNull(sourceId);
        return manualResolutionMapper.selectList(Wrappers.<IntegrationManualResolutionEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("source_system", DEFAULT_SOURCE_SYSTEM)
                        .eq(normalizedResolutionType != null, "resolution_type", normalizedResolutionType)
                        .eq(normalizedSourceObjectType != null, "source_object_type", normalizedSourceObjectType)
                        .eq(sourceKey != null, "source_id", sourceKey)
                        .eq(normalizedStatus != null, "status", normalizedStatus)
                        .orderByDesc("updated_at", "created_at")
                        .last("LIMIT " + safeLimit(limit)))
                .stream().map(MybatisPlusDhbIntegrationStore::manualResolution).toList();
    }

    @Override
    public DhbManualResolutionView createManualResolution(
            UUID tenantId, UUID actorId, DhbManualResolutionCommand command) {
        if (command == null) throw new IllegalArgumentException("manual resolution command is required");
        UUID connectorId = requireUuid(command.connectorId(), "connectorId");
        String resolutionType = allowed(command.resolutionType(), MANUAL_RESOLUTION_TYPES, "resolutionType");
        String sourceObjectType = optionalCode(command.sourceObjectType());
        if (sourceObjectType == null) throw new IllegalArgumentException("sourceObjectType is required");
        String requestedSelectedSourceObjectType = optionalCode(command.selectedSourceObjectType());
        if (requestedSelectedSourceObjectType == null && "TRANSFER_INBOUND_RECEIPT".equals(resolutionType)) {
            requestedSelectedSourceObjectType = "WAREHOUSING_RECEIPT";
        }
        if (requestedSelectedSourceObjectType == null) {
            throw new IllegalArgumentException("selectedSourceObjectType is required");
        }
        String selectedSourceObjectType = requestedSelectedSourceObjectType;
        if ("TRANSFER_INBOUND_RECEIPT".equals(resolutionType)) {
            if (!"ERP_STOCK_OUT".equals(sourceObjectType)) {
                throw new IllegalArgumentException(
                        "TRANSFER_INBOUND_RECEIPT requires sourceObjectType ERP_STOCK_OUT");
            }
            if (!"WAREHOUSING_RECEIPT".equals(selectedSourceObjectType)) {
                throw new IllegalArgumentException(
                        "TRANSFER_INBOUND_RECEIPT requires selectedSourceObjectType WAREHOUSING_RECEIPT");
            }
        }
        String sourceId = required(command.sourceId(), "sourceId");
        String selectedSourceId = required(command.selectedSourceId(), "selectedSourceId");
        String reason = required(command.reason(), "reason");
        String evidenceJson = writeJson(command.evidence() == null ? Map.of() : command.evidence());
        String selectedInternalObjectType = optionalCode(command.selectedInternalObjectType());
        Long selectedInternalObjectId = command.selectedInternalObjectId();

        return transaction.execute(txStatus -> {
            LocalDateTime now = now();
            manualResolutionMapper.update(null, Wrappers.<IntegrationManualResolutionEntity>update()
                    .set("status", "SUPERSEDED")
                    .set("updated_at", now)
                    .set("updated_by", bin(actorId))
                    .setSql("version=version+1")
                    .eq("tenant_id", bin(tenantId))
                    .eq("source_system", DEFAULT_SOURCE_SYSTEM)
                    .eq("connector_id", bin(connectorId))
                    .eq("resolution_type", resolutionType)
                    .eq("source_object_type", sourceObjectType)
                    .eq("source_id", sourceId)
                    .eq("status", "ACTIVE"));

            IntegrationManualResolutionEntity row = new IntegrationManualResolutionEntity();
            row.id = bin(UUID.randomUUID());
            row.tenantId = bin(tenantId);
            row.connectorId = bin(connectorId);
            row.sourceSystem = DEFAULT_SOURCE_SYSTEM;
            row.resolutionType = resolutionType;
            row.sourceObjectType = sourceObjectType;
            row.sourceId = sourceId;
            row.selectedSourceObjectType = selectedSourceObjectType;
            row.selectedSourceId = selectedSourceId;
            row.selectedInternalObjectType = selectedInternalObjectType;
            row.selectedInternalObjectId = selectedInternalObjectId;
            row.evidenceJson = evidenceJson;
            row.reason = truncate(reason, 1000);
            row.status = "ACTIVE";
            row.version = 0L;
            row.createdAt = now;
            row.createdBy = bin(actorId);
            row.updatedAt = now;
            row.updatedBy = bin(actorId);
            manualResolutionMapper.insert(row);
            return manualResolution(row);
        });
    }

    @Override
    public void persistRawLanding(UUID tenantId, UUID connectorId, String sourceObjectType,
                                  String sourceId, Instant sourceUpdatedAt,
                                  Map<String, Object> payload) {
        persistRawLandings(tenantId, connectorId, List.of(
                new RawLanding(sourceObjectType, sourceId, sourceUpdatedAt, payload)));
    }

    @Override
    public void persistRawLandings(UUID tenantId, UUID connectorId, List<RawLanding> values) {
        if (values == null || values.isEmpty()) return;
        List<RawLandingRow> rows = values.stream().map(this::prepareRawLanding).toList();
        Set<RawLandingKey> existingKeys = existingRawLandingKeys(tenantId, connectorId, rows);
        transaction.executeWithoutResult(status -> {
            for (RawLandingRow row : rows) {
                RawLandingKey key = row.key();
                if (existingKeys.contains(key)) continue;
                IntegrationRawLandingEntity entity = new IntegrationRawLandingEntity();
                entity.id = bin(row.id());
                entity.tenantId = bin(tenantId);
                entity.connectorId = bin(connectorId);
                entity.sourceSystem = DEFAULT_SOURCE_SYSTEM;
                entity.sourceObjectType = row.objectType();
                entity.sourceId = row.sourceId();
                entity.sourceVersion = row.sourceVersion();
                entity.sourceUpdatedAt = dateTime(row.sourceUpdatedAt());
                entity.payloadJson = row.payloadJson();
                entity.payloadChecksum = row.checksum();
                entity.receivedAt = now();
                entity.landingStatus = "RECEIVED";
                entity.attempts = 0;
                entity.version = 0L;
                try {
                    rawLandingMapper.insert(entity);
                    existingKeys.add(key);
                } catch (DuplicateKeyException ignored) {
                    // 同一来源同一payload已经落库时保持幂等。
                    existingKeys.add(key);
                }
            }
        });
    }

    private Set<RawLandingKey> existingRawLandingKeys(UUID tenantId, UUID connectorId,
                                                      List<RawLandingRow> rows) {
        if (rows == null || rows.isEmpty()) return Set.of();
        Map<String, List<RawLandingRow>> rowsByType = new LinkedHashMap<>();
        for (RawLandingRow row : rows) {
            rowsByType.computeIfAbsent(row.objectType(), ignored -> new ArrayList<>()).add(row);
        }
        Set<RawLandingKey> result = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, List<RawLandingRow>> entry : rowsByType.entrySet()) {
            List<String> sourceIds = entry.getValue().stream()
                    .map(RawLandingRow::sourceId)
                    .distinct()
                    .toList();
            List<String> checksums = entry.getValue().stream()
                    .map(RawLandingRow::checksum)
                    .distinct()
                    .toList();
            for (IntegrationRawLandingEntity row : rawLandingMapper.selectList(
                    Wrappers.<IntegrationRawLandingEntity>query()
                            .eq("tenant_id", bin(tenantId))
                            .eq("connector_id", bin(connectorId))
                            .eq("source_system", DEFAULT_SOURCE_SYSTEM)
                            .eq("source_object_type", entry.getKey())
                            .in("source_id", sourceIds)
                            .in("payload_checksum", checksums))) {
                result.add(new RawLandingKey(row.sourceObjectType, row.sourceId, row.payloadChecksum));
            }
        }
        return result;
    }

    private QueryWrapper<ExternalObjectMappingEntity> externalMappingQuery(
            UUID tenantId, String sourceObjectType, String internalDomain, String mappingStatus) {
        String sourceType = optionalCode(sourceObjectType);
        String domain = optionalCode(internalDomain);
        String status = optionalCode(mappingStatus);
        return Wrappers.<ExternalObjectMappingEntity>query()
                .eq("tenant_id", bin(tenantId))
                .isNull("deleted_at")
                .eq(sourceType != null, "source_object_type", sourceType)
                .eq(domain != null, "internal_domain", domain)
                .eq(status != null, "mapping_status", status);
    }

    private void saveExternalObjectMapping(UUID tenantId, UUID actorId,
                                           ExternalObjectMappingCommand command,
                                           Map<ExternalMappingKey, ExternalObjectMappingEntity> existingRows) {
        if (command == null) throw new IllegalArgumentException("mapping command is required");
        UUID connectorId = requireUuid(command.connectorId(), "connectorId");
        String sourceSystem = optionalCode(command.sourceSystem());
        if (sourceSystem == null) sourceSystem = DEFAULT_SOURCE_SYSTEM;
        String sourceType = optionalCode(command.sourceObjectType());
        if (sourceType == null) throw new IllegalArgumentException("sourceObjectType is required");
        String sourceId = required(command.sourceObjectId(), "sourceObjectId");
        String mappingStatus = allowed(
                command.mappingStatus() == null || command.mappingStatus().isBlank()
                        ? "ACTIVE" : command.mappingStatus(),
                MAPPING_STATUSES, "mappingStatus");
        String internalDomain = optionalCode(command.internalDomain());
        String internalObjectType = optionalCode(command.internalObjectType());
        if ("ACTIVE".equals(mappingStatus)
                && (internalDomain == null || internalObjectType == null
                || command.internalObjectId() == null)) {
            throw new IllegalArgumentException("ACTIVE mapping requires internal object fields");
        }

        LocalDateTime now = now();
        ExternalMappingKey key = new ExternalMappingKey(connectorId, sourceSystem, sourceType, sourceId);
        ExternalObjectMappingEntity row = existingRows == null ? null : existingRows.get(key);
        if (row == null && existingRows == null) {
            row = externalObjectMappingRow(tenantId, connectorId, sourceSystem, sourceType, sourceId);
        }
        if (row == null) {
            row = new ExternalObjectMappingEntity();
            row.id = bin(UUID.randomUUID());
            row.tenantId = bin(tenantId);
            row.connectorId = bin(connectorId);
            row.sourceSystem = sourceSystem;
            row.sourceObjectType = sourceType;
            row.sourceObjectId = sourceId;
            row.version = 0L;
            row.createdAt = now;
            row.createdBy = bin(actorId);
            applyExternalObjectMapping(row, command, mappingStatus, internalDomain,
                    internalObjectType, now, actorId);
            externalObjectMappingMapper.insert(row);
            if (existingRows != null) existingRows.put(key, row);
        } else {
            applyExternalObjectMapping(row, command, mappingStatus, internalDomain,
                    internalObjectType, now, actorId);
            row.version = zero(row.version) + 1;
            externalObjectMappingMapper.updateById(row);
        }
    }

    private Map<ExternalMappingKey, ExternalObjectMappingEntity> externalObjectMappingRows(
            UUID tenantId, List<ExternalObjectMappingCommand> commands) {
        Map<ExternalMappingGroup, List<String>> sourceIds = new LinkedHashMap<>();
        for (ExternalObjectMappingCommand command : commands) {
            if (command == null) continue;
            UUID connectorId = command.connectorId();
            String sourceSystem = optionalCode(command.sourceSystem());
            if (sourceSystem == null) sourceSystem = DEFAULT_SOURCE_SYSTEM;
            String sourceType = optionalCode(command.sourceObjectType());
            String sourceId = blankToNull(command.sourceObjectId());
            if (connectorId == null || sourceType == null || sourceId == null) continue;
            ExternalMappingGroup group = new ExternalMappingGroup(connectorId, sourceSystem, sourceType);
            sourceIds.computeIfAbsent(group, ignored -> new ArrayList<>()).add(sourceId);
        }
        Map<ExternalMappingKey, ExternalObjectMappingEntity> result = new LinkedHashMap<>();
        for (Map.Entry<ExternalMappingGroup, List<String>> entry : sourceIds.entrySet()) {
            ExternalMappingGroup group = entry.getKey();
            List<String> ids = entry.getValue().stream().distinct().toList();
            List<ExternalObjectMappingEntity> rows = externalObjectMappingMapper.selectList(
                    Wrappers.<ExternalObjectMappingEntity>query()
                            .eq("tenant_id", bin(tenantId))
                            .eq("connector_id", bin(group.connectorId()))
                            .eq("source_system", group.sourceSystem())
                            .eq("source_object_type", group.sourceType())
                            .in("source_object_id", ids));
            for (ExternalObjectMappingEntity row : rows) {
                result.put(new ExternalMappingKey(
                        IntegrationUuidCodec.decode(row.connectorId),
                        row.sourceSystem,
                        row.sourceObjectType,
                        row.sourceObjectId), row);
            }
        }
        return result;
    }

    private ExternalObjectMappingEntity externalObjectMappingRow(
            UUID tenantId, UUID connectorId, String sourceSystem, String sourceType, String sourceId) {
        return first(externalObjectMappingMapper.selectList(Wrappers.<ExternalObjectMappingEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("connector_id", bin(connectorId))
                .eq("source_system", sourceSystem)
                .eq("source_object_type", sourceType)
                .eq("source_object_id", sourceId)
                .last("LIMIT 1")));
    }

    private record ExternalMappingGroup(UUID connectorId, String sourceSystem, String sourceType) {
    }

    private record ExternalMappingKey(UUID connectorId, String sourceSystem, String sourceType, String sourceId) {
    }

    private static void applyExternalObjectMapping(
            ExternalObjectMappingEntity row,
            ExternalObjectMappingCommand command,
            String mappingStatus,
            String internalDomain,
            String internalObjectType,
            LocalDateTime now,
            UUID actorId) {
        row.sourceObjectNo = blankToNull(command.sourceObjectNo());
        row.internalDomain = internalDomain;
        row.internalObjectType = internalObjectType;
        row.internalObjectId = command.internalObjectId();
        row.internalObjectNo = blankToNull(command.internalObjectNo());
        row.mappingStatus = mappingStatus;
        row.lastSeenRunId = command.lastSeenRunId() == null ? null : bin(command.lastSeenRunId());
        row.lastSeenAt = command.lastSeenAt() == null ? now : dateTime(command.lastSeenAt());
        row.sourceDeletedAt = dateTime(command.sourceDeletedAt());
        row.payloadChecksum = checksum(command.payloadChecksum());
        row.conflictReason = truncate(command.conflictReason(), 1000);
        row.remark = truncate(command.remark(), 1000);
        row.updatedAt = now;
        row.updatedBy = bin(actorId);
        row.deletedAt = null;
        row.deletedBy = null;
        row.deleteReason = null;
    }

    private RawLandingRow prepareRawLanding(RawLanding value) {
        String objectType = required(value.sourceObjectType(), "sourceObjectType");
        String sourceId = required(value.sourceId(), "sourceId");
        if (value.payload() == null) throw new IllegalArgumentException("payload is required");
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(value.payload());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订货宝原始回执序列化失败", exception);
        }
        return new RawLandingRow(UUID.randomUUID(), objectType, sourceId, value.sourceUpdatedAt(),
                value.sourceUpdatedAt() == null ? null : value.sourceUpdatedAt().toString(),
                payloadJson, sha256(payloadJson));
    }

    private ConnectorView connectorById(UUID tenantId, UUID id) {
        DhbConnectorEntity row = connectorRow(tenantId, id);
        if (row == null) throw new AuthorizationDeniedException("integration:dhb:connector");
        return connector(row);
    }

    private SyncTaskView syncTaskById(UUID tenantId, UUID id) {
        IntegrationSyncTaskEntity row = taskById(tenantId, id);
        if (row == null) throw new IllegalStateException("同步任务不存在");
        return syncTask(row);
    }

    private FieldMappingView fieldMappingById(UUID tenantId, UUID id) {
        IntegrationFieldMappingEntity row = first(fieldMappingMapper.selectList(
                Wrappers.<IntegrationFieldMappingEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("id", bin(id))
                        .last("LIMIT 1")));
        if (row == null) throw new IllegalStateException("字段映射不存在");
        return fieldMapping(row);
    }

    private DhbConnectorEntity connectorRow(UUID tenantId, UUID connectorId) {
        if (connectorId == null) return null;
        return first(connectorMapper.selectList(Wrappers.<DhbConnectorEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(connectorId))
                .isNull("deleted_at")
                .last("LIMIT 1")));
    }

    private IntegrationSyncTaskEntity taskById(UUID tenantId, UUID taskId) {
        if (taskId == null) return null;
        return first(taskMapper.selectList(Wrappers.<IntegrationSyncTaskEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(taskId))
                .last("LIMIT 1")));
    }

    private void requireConnector(UUID tenantId, UUID connectorId) {
        if (connectorRow(tenantId, connectorId) == null) {
            throw new AuthorizationDeniedException("integration:dhb:connector");
        }
    }

    private void ensureDefaultSyncTasks(UUID tenantId, UUID actorId, UUID connectorId) {
        ensureDefaultSyncTask(tenantId, actorId, connectorId, DEFAULT_ORDER_TASK_CODE, "ORDER");
        ensureDefaultSyncTask(tenantId, actorId, connectorId, DEFAULT_PRODUCT_MASTER_TASK_CODE, "PRODUCT_MASTER_DATA");
        ensureDefaultSyncTask(tenantId, actorId, connectorId, DEFAULT_SUPPLY_CHAIN_TASK_CODE, "SUPPLY_CHAIN_DATA");
        ensureDefaultSyncTask(tenantId, actorId, connectorId, DEFAULT_CRM_MASTER_TASK_CODE, "CRM_MASTER_DATA");
        ensureDefaultSyncTask(tenantId, actorId, connectorId,
                DEFAULT_BUSINESS_DICTIONARY_TASK_CODE, "BUSINESS_DICTIONARY");
    }

    private void ensureDefaultSyncTask(UUID tenantId, UUID actorId, UUID connectorId,
                                       String taskCode, String objectType) {
        long activeCount = taskMapper.selectCount(Wrappers.<IntegrationSyncTaskEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("connector_id", bin(connectorId))
                .eq("object_type", objectType)
                .isNull("deleted_at"));
        if (activeCount > 0) return;
        int restored = taskMapper.update(null, Wrappers.<IntegrationSyncTaskEntity>update()
                .set("object_type", objectType)
                .set("task_status", "IDLE")
                .set("enabled", 1)
                .set("deleted_at", null)
                .set("deleted_by", null)
                .set("delete_reason", null)
                .set("updated_at", now())
                .set("updated_by", bin(actorId))
                .setSql("version=version+1")
                .eq("tenant_id", bin(tenantId))
                .eq("connector_id", bin(connectorId))
                .eq("task_code", taskCode)
                .isNotNull("deleted_at"));
        if (restored == 1) return;

        LocalDateTime now = now();
        IntegrationSyncTaskEntity row = new IntegrationSyncTaskEntity();
        row.id = bin(UUID.randomUUID());
        row.tenantId = bin(tenantId);
        row.connectorId = bin(connectorId);
        row.taskCode = taskCode;
        row.objectType = objectType;
        row.taskStatus = "IDLE";
        row.version = 0L;
        row.createdAt = now;
        row.createdBy = bin(actorId);
        row.updatedAt = now;
        row.updatedBy = bin(actorId);
        row.enabled = 1;
        taskMapper.insert(row);
    }

    private void requireNoExistingOrderSyncTask(UUID tenantId, UUID connectorId, UUID ignoredTaskId) {
        QueryWrapper<IntegrationSyncTaskEntity> query = Wrappers.<IntegrationSyncTaskEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("connector_id", bin(connectorId))
                .eq("object_type", "ORDER")
                .isNull("deleted_at");
        if (ignoredTaskId != null) query.ne("id", bin(ignoredTaskId));
        if (taskMapper.selectCount(query) > 0) {
            throw new IllegalArgumentException("每个订货宝连接器只允许一个ORDER同步任务，系统已自动创建默认任务");
        }
    }

    private static void validateDefaultTaskType(String taskCode, String objectType) {
        if (DEFAULT_ORDER_TASK_CODE.equals(taskCode) && !"ORDER".equals(objectType)) {
            throw new IllegalArgumentException("系统默认订单同步任务不能修改对象类型");
        }
        if (DEFAULT_PRODUCT_MASTER_TASK_CODE.equals(taskCode) && !"PRODUCT_MASTER_DATA".equals(objectType)) {
            throw new IllegalArgumentException("系统默认商品主数据同步任务不能修改对象类型");
        }
        if (DEFAULT_SUPPLY_CHAIN_TASK_CODE.equals(taskCode) && !"SUPPLY_CHAIN_DATA".equals(objectType)) {
            throw new IllegalArgumentException("系统默认供应链数据同步任务不能修改对象类型");
        }
        if (DEFAULT_CRM_MASTER_TASK_CODE.equals(taskCode) && !"CRM_MASTER_DATA".equals(objectType)) {
            throw new IllegalArgumentException("系统默认CRM主数据同步任务不能修改对象类型");
        }
        if (DEFAULT_BUSINESS_DICTIONARY_TASK_CODE.equals(taskCode) && !"BUSINESS_DICTIONARY".equals(objectType)) {
            throw new IllegalArgumentException("系统默认字典同步任务不能修改对象类型");
        }
    }

    private static ConnectorView connector(DhbConnectorEntity row) {
        return new ConnectorView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId), row.connectorCode, row.connectorName,
                row.baseUrl, row.authSecretRef, row.status, zero(row.version));
    }

    private static SyncTaskView syncTask(IntegrationSyncTaskEntity row) {
        return new SyncTaskView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId), IntegrationUuidCodec.decode(row.connectorId),
                row.taskCode, row.objectType, row.taskStatus, instant(row.lastRunAt),
                instant(row.nextRunAt), zero(row.version));
    }

    private static OrderMirrorView orderMirror(IntegrationOrderMirrorEntity row) {
        return new OrderMirrorView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId), IntegrationUuidCodec.decode(row.connectorId),
                row.sourceOrderId, row.orderNo, row.sourceStatus, row.amount,
                instant(row.orderTime), row.mirrorStatus, zero(row.version));
    }

    private static SyncLogView syncLog(IntegrationSyncLogEntity row) {
        return new SyncLogView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId), IntegrationUuidCodec.decode(row.taskId),
                IntegrationUuidCodec.decode(row.runId), row.logLevel, row.message,
                row.errorCode, instant(row.occurredAt));
    }

    private static FieldMappingView fieldMapping(IntegrationFieldMappingEntity row) {
        return new FieldMappingView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId), IntegrationUuidCodec.decode(row.connectorId),
                row.sourceField, row.targetField, row.transformType,
                row.enabled != null && row.enabled == 1, zero(row.version));
    }

    private static DhbExternalObjectMappingView externalObjectMapping(ExternalObjectMappingEntity row) {
        return new DhbExternalObjectMappingView(
                IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId),
                IntegrationUuidCodec.decode(row.connectorId),
                row.sourceSystem, row.sourceObjectType, row.sourceObjectId, row.sourceObjectNo,
                row.internalDomain, row.internalObjectType, row.internalObjectId, row.internalObjectNo,
                row.mappingStatus, IntegrationUuidCodec.decode(row.lastSeenRunId),
                instant(row.lastSeenAt), instant(row.sourceDeletedAt), row.payloadChecksum,
                row.conflictReason, row.remark, zero(row.version), instant(row.createdAt),
                instant(row.updatedAt));
    }

    private static DhbSyncRunAuditView syncRunAudit(IntegrationSyncRunEntity run,
                                                    IntegrationSyncTaskEntity task) {
        return new DhbSyncRunAuditView(IntegrationUuidCodec.decode(run.id),
                IntegrationUuidCodec.decode(run.tenantId), IntegrationUuidCodec.decode(run.taskId),
                IntegrationUuidCodec.decode(task.connectorId), task.taskCode, task.objectType,
                run.triggerType, run.status, instant(run.windowFrom), instant(run.windowTo),
                zero(run.fetchedCount), zero(run.acceptedCount), zero(run.duplicateCount),
                zero(run.rejectedCount), instant(run.startedAt), instant(run.finishedAt),
                run.errorCode, run.errorMessage);
    }

    private static DhbSyncLogDetailView syncLogDetail(IntegrationSyncLogEntity log,
                                                      IntegrationSyncTaskEntity task) {
        return new DhbSyncLogDetailView(IntegrationUuidCodec.decode(log.id),
                IntegrationUuidCodec.decode(log.tenantId), IntegrationUuidCodec.decode(log.taskId),
                IntegrationUuidCodec.decode(log.runId),
                task == null ? null : IntegrationUuidCodec.decode(task.connectorId),
                task == null ? null : task.taskCode, task == null ? null : task.objectType,
                log.logLevel, log.message, log.errorCode, instant(log.occurredAt));
    }

    private static DhbSyncExceptionView syncException(IntegrationDeadLetterEntity row) {
        return new DhbSyncExceptionView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId), IntegrationUuidCodec.decode(row.runId),
                IntegrationUuidCodec.decode(row.rawLandingId), row.sourceSystem, row.sourceObjectType,
                row.sourceId, row.status, row.attempts == null ? 0 : row.attempts,
                instant(row.nextRetryAt), row.lastErrorCode, row.lastErrorMessage,
                instant(row.resolvedAt), instant(row.createdAt), instant(row.updatedAt));
    }

    private static DhbSyncReconciliationCaseView reconciliationCase(
            IntegrationReconciliationCaseEntity row) {
        return new DhbSyncReconciliationCaseView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId), IntegrationUuidCodec.decode(row.runId),
                row.sourceSystem, row.sourceObjectType, row.businessKey, row.checkType,
                row.expectedValueJson, row.actualValueJson, row.status, row.severity, row.message,
                instant(row.resolvedAt), instant(row.createdAt), instant(row.updatedAt));
    }

    private static DhbManualResolutionView manualResolution(IntegrationManualResolutionEntity row) {
        return new DhbManualResolutionView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.tenantId),
                IntegrationUuidCodec.decode(row.connectorId), row.sourceSystem,
                row.resolutionType, row.sourceObjectType, row.sourceId,
                row.selectedSourceObjectType, row.selectedSourceId,
                row.selectedInternalObjectType, row.selectedInternalObjectId,
                row.evidenceJson, row.reason, row.status,
                instant(row.createdAt), instant(row.updatedAt));
    }

    private static <T> T first(List<T> rows) {
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private static byte[] bin(UUID value) {
        return IntegrationUuidCodec.encode(value);
    }

    private static UUID requireUuid(UUID value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
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

    private static long zero(Long value) {
        return value == null ? 0L : value;
    }

    private static int safeLimit(int value) {
        return value < 1 ? 20 : Math.min(value, 500);
    }

    private static int safeOffset(int value) {
        return Math.max(value, 0);
    }

    private static void requireChanged(int changed) {
        if (changed != 1) throw new IllegalStateException("Record changed concurrently or no longer exists");
    }

    private String writeJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订货宝人工裁决证据序列化失败", exception);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value) {
        if (value == null) return null;
        String oneLine = value.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() > 2000 ? oneLine.substring(0, 2000) : oneLine;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').strip();
        if (oneLine.isEmpty()) return null;
        return oneLine.length() > max ? oneLine.substring(0, max) : oneLine;
    }

    private static String checksum(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        if (normalized.length() > 64) throw new IllegalArgumentException("payloadChecksum is too long");
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static String normalizedCode(String value) {
        String code = required(value, "code").strip();
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("code contains unsupported characters");
        }
        return code;
    }

    private static String normalizedObjectType(String value) {
        return required(value, "objectType").toUpperCase(Locale.ROOT);
    }

    private static String optionalCode(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private static String allowed(String value, Set<String> values, String field) {
        String normalized = required(value, field).toUpperCase(Locale.ROOT);
        if (!values.contains(normalized)) throw new IllegalArgumentException("Invalid " + field);
        return normalized;
    }

    private static void validateConnector(ConnectorCommand command) {
        required(command.code(), "code");
        required(command.name(), "name");
        required(command.baseUrl(), "baseUrl");
        required(command.authSecretRef(), "authSecretRef");
    }

    private static void validateSyncTask(SyncTaskCommand command) {
        if (command.connectorId() == null) throw new IllegalArgumentException("connectorId is required");
        required(command.code(), "code");
        required(command.objectType(), "objectType");
    }

    private static void validateFieldMapping(FieldMappingCommand command) {
        if (command.connectorId() == null) throw new IllegalArgumentException("connectorId is required");
        required(command.sourceField(), "sourceField");
        required(command.targetField(), "targetField");
    }

    private record RawLandingRow(UUID id, String objectType, String sourceId,
                                 Instant sourceUpdatedAt, String sourceVersion,
                                 String payloadJson, String checksum) {
        private RawLandingKey key() {
            return new RawLandingKey(objectType, sourceId, checksum);
        }
    }

    private record RawLandingKey(String objectType, String sourceId, String checksum) {
    }
}
