package com.rigour.integration.infrastructure.persistence;

import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderMirrorView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncLogView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 订货宝同步JDBC仓储；所有查询强制绑定tenantId，跨租户访问直接拒绝。 */
public final class JdbcDhbIntegrationStore implements DhbIntegrationStore {
    private static final String DEFAULT_ORDER_TASK_CODE = "DHB_ORDER_DEFAULT";
    private static final String DEFAULT_PRODUCT_MASTER_TASK_CODE = "DHB_PRODUCT_MASTER_DEFAULT";
    private static final String DEFAULT_SUPPLY_CHAIN_TASK_CODE = "DHB_SUPPLY_CHAIN_DEFAULT";
    private static final Set<String> CONNECTOR_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> TASK_STATUSES = Set.of("IDLE", "RUNNING", "PAUSED", "FAILED", "COMPLETED");
    private static final Set<String> TRANSFORM_TYPES = Set.of("DIRECT", "CONSTANT", "EXPRESSION", "DICTIONARY");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public JdbcDhbIntegrationStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                   ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ConnectorView> connectors(UUID tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, connector_code, connector_name, base_url, auth_secret_ref, status, version
                  FROM integration_dhb_connector
                 WHERE tenant_id=? AND deleted_at IS NULL
                 ORDER BY connector_code
                """, (rs, row) -> connector(rs), bin(tenantId));
    }

    @Override
    public ConnectorView connector(UUID tenantId, UUID connectorId) {
        if (connectorId == null) {
            throw new AuthorizationDeniedException("integration:dhb:connector");
        }
        List<ConnectorView> rows = jdbc.query("""
                SELECT id, tenant_id, connector_code, connector_name, base_url, auth_secret_ref, status, version
                  FROM integration_dhb_connector
                 WHERE tenant_id=? AND id=? AND deleted_at IS NULL
                """, (rs, row) -> connector(rs), bin(tenantId), bin(connectorId));
        if (rows.isEmpty()) {
            throw new AuthorizationDeniedException("integration:dhb:connector");
        }
        return rows.getFirst();
    }

    @Override
    public void recordConnectionTest(UUID tenantId, UUID actorId, UUID connectorId,
                                     ConnectionTestResult result) {
        if (connectorId == null || result == null) {
            throw new IllegalArgumentException("connectorId and result are required");
        }
        String credentialStatus = result.success() ? "VALID"
                : ("DHB_AUTH_FAILED".equals(result.code()) ? "INVALID" : "UNKNOWN");
        String errorMessage = result.message() == null ? null
                : result.message().substring(0, Math.min(result.message().length(), 2000));
        int changed = jdbc.update("""
                UPDATE integration_dhb_connector
                   SET credential_status=?, last_checked_at=UTC_TIMESTAMP(6),
                       last_error_code=?, last_error_message=?, version=version+1,
                       updated_at=UTC_TIMESTAMP(6), updated_by=?
                 WHERE tenant_id=? AND id=? AND deleted_at IS NULL
                """, credentialStatus, result.success() ? null : result.code(), errorMessage,
                bin(actorId), bin(tenantId), bin(connectorId));
        requireChanged(changed);
    }

    @Override
    public ConnectorView createConnector(UUID tenantId, UUID actorId, ConnectorCommand command) {
        validateConnector(command);
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO integration_dhb_connector
                    (id, tenant_id, connector_code, connector_name, base_url, auth_secret_ref, status,
                     created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(tenantId), normalizedCode(command.code()), required(command.name(), "name"),
                    blankToNull(command.baseUrl()), blankToNull(command.authSecretRef()),
                    allowed(command.status(), CONNECTOR_STATUSES, "status"), bin(actorId), bin(actorId));
            ensureDefaultSyncTasks(tenantId, actorId, id);
        });
        return connectorById(tenantId, id);
    }

    @Override
    public ConnectorView updateConnector(UUID tenantId, UUID actorId, UUID id, ConnectorCommand command) {
        validateConnector(command);
        String connectorStatus = allowed(command.status(), CONNECTOR_STATUSES, "status");
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE integration_dhb_connector
                       SET connector_name=?, base_url=?, auth_secret_ref=?, status=?,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND connector_code=? AND version=? AND deleted_at IS NULL
                    """, required(command.name(), "name"), blankToNull(command.baseUrl()),
                    blankToNull(command.authSecretRef()), connectorStatus,
                    bin(actorId), bin(tenantId), bin(id), normalizedCode(command.code()), command.version());
            requireChanged(changed);
            if ("ACTIVE".equals(connectorStatus)) ensureDefaultSyncTasks(tenantId, actorId, id);
        });
        return connectorById(tenantId, id);
    }

    @Override
    public List<SyncTaskView> syncTasks(UUID tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, connector_id, task_code, object_type, task_status,
                       last_run_at, next_run_at, version
                  FROM integration_sync_task
                 WHERE tenant_id=? AND deleted_at IS NULL
                ORDER BY task_code
                """, (rs, row) -> syncTask(rs), bin(tenantId));
    }

    @Override
    public List<SyncTargetView> activeSyncTargets(String objectType) {
        String normalizedType = normalizedObjectType(objectType);
        return jdbc.query("""
                SELECT t.id AS task_id, t.tenant_id, t.connector_id
                  FROM integration_sync_task t
                  JOIN integration_dhb_connector c
                    ON c.tenant_id=t.tenant_id AND c.id=t.connector_id
                 WHERE t.object_type=?
                   AND t.enabled=1
                   AND t.task_status<>'PAUSED'
                   AND t.deleted_at IS NULL
                   AND c.status='ACTIVE'
                   AND c.deleted_at IS NULL
                 ORDER BY t.tenant_id, t.id
                """, (rs, row) -> new SyncTargetView(
                IntegrationUuidCodec.decode(rs, "task_id"),
                IntegrationUuidCodec.decode(rs, "tenant_id"),
                IntegrationUuidCodec.decode(rs, "connector_id")), normalizedType);
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
            jdbc.update("""
                    INSERT INTO integration_sync_task
                    (id, tenant_id, connector_id, task_code, object_type, task_status,
                     next_run_at, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(tenantId), bin(command.connectorId()), normalizedCode(command.code()),
                    objectType,
                    allowed(command.status(), TASK_STATUSES, "status"), timestamp(command.nextRunAt()),
                    bin(actorId), bin(actorId));
        });
        return syncTaskById(tenantId, id);
    }

    @Override
    public SyncTaskView updateSyncTask(UUID tenantId, UUID actorId, UUID id, SyncTaskCommand command) {
        validateSyncTask(command);
        requireConnector(tenantId, command.connectorId());
        String taskCode = normalizedCode(command.code());
        String objectType = normalizedObjectType(command.objectType());
        if (DEFAULT_ORDER_TASK_CODE.equals(taskCode) && !"ORDER".equals(objectType)) {
            throw new IllegalArgumentException("系统默认订单同步任务不能修改对象类型");
        }
        if (DEFAULT_PRODUCT_MASTER_TASK_CODE.equals(taskCode)
                && !"PRODUCT_MASTER_DATA".equals(objectType)) {
            throw new IllegalArgumentException("系统默认商品主数据同步任务不能修改对象类型");
        }
        if (DEFAULT_SUPPLY_CHAIN_TASK_CODE.equals(taskCode)
                && !"SUPPLY_CHAIN_DATA".equals(objectType)) {
            throw new IllegalArgumentException("系统默认供应链数据同步任务不能修改对象类型");
        }
        if ("ORDER".equals(objectType)) {
            requireNoExistingOrderSyncTask(tenantId, command.connectorId(), id);
        }
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE integration_sync_task
                       SET connector_id=?, object_type=?, task_status=?, next_run_at=?,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND task_code=? AND version=? AND deleted_at IS NULL
                    """, bin(command.connectorId()), objectType,
                    allowed(command.status(), TASK_STATUSES, "status"), timestamp(command.nextRunAt()),
                    bin(actorId), bin(tenantId), bin(id), taskCode, command.version());
            requireChanged(changed);
        });
        return syncTaskById(tenantId, id);
    }

    @Override
    public List<OrderMirrorView> orderMirrors(UUID tenantId, int limit, int offset) {
        return jdbc.query("""
                SELECT id, tenant_id, source_order_id, order_no, source_status, amount, order_time,
                       mirror_status, version
                  FROM integration_order_mirror
                 WHERE tenant_id=?
                 ORDER BY order_time DESC, created_at DESC
                 LIMIT ? OFFSET ?
                """, (rs, row) -> orderMirror(rs), bin(tenantId), limit, offset);
    }

    @Override
    public List<SyncLogView> syncLogs(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT id, tenant_id, task_id, run_id, log_level, message, error_code, occurred_at
                  FROM integration_sync_log
                 WHERE tenant_id=?
                 ORDER BY occurred_at DESC
                 LIMIT ?
                """, (rs, row) -> syncLog(rs), bin(tenantId), limit);
    }

    @Override
    public List<FieldMappingView> fieldMappings(UUID tenantId, UUID connectorId) {
        requireConnector(tenantId, connectorId);
        return jdbc.query("""
                SELECT id, tenant_id, connector_id, source_field, target_field, transform_type,
                       enabled, version
                  FROM integration_field_mapping
                 WHERE tenant_id=? AND connector_id=? AND deleted_at IS NULL
                 ORDER BY source_field
                """, (rs, row) -> fieldMapping(rs), bin(tenantId), bin(connectorId));
    }

    @Override
    public FieldMappingView saveFieldMapping(UUID tenantId, UUID actorId, UUID id,
                                             FieldMappingCommand command) {
        validateFieldMapping(command);
        requireConnector(tenantId, command.connectorId());
        UUID targetId = id == null ? UUID.randomUUID() : id;
        transaction.executeWithoutResult(status -> {
            if (id == null) {
                jdbc.update("""
                        INSERT INTO integration_field_mapping
                        (id, tenant_id, connector_id, source_field, target_field, transform_type, enabled,
                         created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                        """, bin(targetId), bin(tenantId), bin(command.connectorId()),
                        normalizedCode(command.sourceField()), normalizedCode(command.targetField()),
                        allowed(command.transformType(), TRANSFORM_TYPES, "transformType"),
                        command.enabled(), bin(actorId), bin(actorId));
            } else {
                int changed = jdbc.update("""
                        UPDATE integration_field_mapping
                           SET target_field=?, transform_type=?, enabled=?, version=version+1,
                               updated_at=UTC_TIMESTAMP(6), updated_by=?
                         WHERE tenant_id=? AND id=? AND connector_id=? AND source_field=?
                           AND version=? AND deleted_at IS NULL
                        """, normalizedCode(command.targetField()),
                        allowed(command.transformType(), TRANSFORM_TYPES, "transformType"),
                        command.enabled(), bin(actorId), bin(tenantId), bin(id), bin(command.connectorId()),
                        normalizedCode(command.sourceField()), command.version());
                requireChanged(changed);
            }
        });
        return fieldMappingById(tenantId, targetId);
    }

    @Override
    public void persistRawLanding(UUID tenantId, UUID connectorId, String sourceObjectType,
                                  String sourceId, Instant sourceUpdatedAt,
                                  java.util.Map<String, Object> payload) {
        String objectType = required(sourceObjectType, "sourceObjectType");
        String businessId = required(sourceId, "sourceId");
        if (payload == null) throw new IllegalArgumentException("payload is required");
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订货宝原始回执序列化失败", exception);
        }
        String checksum = sha256(payloadJson);
        transaction.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO integration_raw_landing
                    (id, tenant_id, connector_id, source_system, source_object_type, source_id,
                     source_version, source_updated_at, payload_json, payload_checksum,
                     received_at, landing_status, attempts, last_attempt_at)
                VALUES (?, ?, ?, 'DHB', ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), 'RECEIVED', 0, NULL)
                ON DUPLICATE KEY UPDATE id=id
                """, bin(UUID.randomUUID()), bin(tenantId), bin(connectorId), objectType, businessId,
                sourceUpdatedAt == null ? null : sourceUpdatedAt.toString(), timestamp(sourceUpdatedAt),
                payloadJson, checksum));
    }

    private ConnectorView connectorById(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT id, tenant_id, connector_code, connector_name, base_url, auth_secret_ref, status, version
                  FROM integration_dhb_connector WHERE tenant_id=? AND id=?
                """, (rs, row) -> connector(rs), bin(tenantId), bin(id));
    }

    private SyncTaskView syncTaskById(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT id, tenant_id, connector_id, task_code, object_type, task_status,
                       last_run_at, next_run_at, version
                  FROM integration_sync_task WHERE tenant_id=? AND id=?
                """, (rs, row) -> syncTask(rs), bin(tenantId), bin(id));
    }

    private FieldMappingView fieldMappingById(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT id, tenant_id, connector_id, source_field, target_field, transform_type,
                       enabled, version
                  FROM integration_field_mapping WHERE tenant_id=? AND id=?
                """, (rs, row) -> fieldMapping(rs), bin(tenantId), bin(id));
    }

    private void requireConnector(UUID tenantId, UUID connectorId) {
        if (connectorId == null || count("""
                SELECT COUNT(*) FROM integration_dhb_connector
                 WHERE tenant_id=? AND id=? AND deleted_at IS NULL
                """, bin(tenantId), bin(connectorId)) != 1) {
            throw new AuthorizationDeniedException("integration:dhb:connector");
        }
    }

    /**
     * 每个订货宝连接器默认只维护一个订单域同步任务；收款、付款等扩展对象仍可单独配置。
     * 该方法必须在连接器事务内调用，保证连接器创建与默认任务创建保持原子性。
     */
    private void ensureDefaultOrderSyncTask(UUID tenantId, UUID actorId, UUID connectorId) {
        if (count("""
                SELECT COUNT(*) FROM integration_sync_task
                 WHERE tenant_id=? AND connector_id=? AND object_type='ORDER' AND deleted_at IS NULL
                """, bin(tenantId), bin(connectorId)) > 0) {
            return;
        }
        int restored = jdbc.update("""
                UPDATE integration_sync_task
                   SET object_type='ORDER', task_status='IDLE', enabled=1,
                       deleted_at=NULL, deleted_by=NULL, delete_reason=NULL,
                       version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                 WHERE tenant_id=? AND connector_id=? AND task_code=? AND deleted_at IS NOT NULL
                """, bin(actorId), bin(tenantId), bin(connectorId), DEFAULT_ORDER_TASK_CODE);
        if (restored == 1) return;

        jdbc.update("""
                INSERT INTO integration_sync_task
                    (id, tenant_id, connector_id, task_code, object_type, task_status,
                     next_run_at, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'ORDER', 'IDLE', NULL, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                """, bin(UUID.randomUUID()), bin(tenantId), bin(connectorId), DEFAULT_ORDER_TASK_CODE,
                bin(actorId), bin(actorId));
    }

    private void ensureDefaultSyncTasks(UUID tenantId, UUID actorId, UUID connectorId) {
        ensureDefaultOrderSyncTask(tenantId, actorId, connectorId);
        ensureDefaultProductMasterSyncTask(tenantId, actorId, connectorId);
        ensureDefaultSupplyChainSyncTask(tenantId, actorId, connectorId);
    }

    private void ensureDefaultSupplyChainSyncTask(UUID tenantId, UUID actorId, UUID connectorId) {
        if (count("""
                SELECT COUNT(*) FROM integration_sync_task
                 WHERE tenant_id=? AND connector_id=? AND object_type='SUPPLY_CHAIN_DATA'
                   AND deleted_at IS NULL
                """, bin(tenantId), bin(connectorId)) > 0) return;
        int restored = jdbc.update("""
                UPDATE integration_sync_task
                   SET object_type='SUPPLY_CHAIN_DATA', task_status='IDLE', enabled=1,
                       deleted_at=NULL, deleted_by=NULL, delete_reason=NULL,
                       version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                 WHERE tenant_id=? AND connector_id=? AND task_code=? AND deleted_at IS NOT NULL
                """, bin(actorId), bin(tenantId), bin(connectorId), DEFAULT_SUPPLY_CHAIN_TASK_CODE);
        if (restored == 1) return;
        jdbc.update("""
                INSERT INTO integration_sync_task
                    (id, tenant_id, connector_id, task_code, object_type, task_status,
                     next_run_at, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'SUPPLY_CHAIN_DATA', 'IDLE', NULL,
                        UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                """, bin(UUID.randomUUID()), bin(tenantId), bin(connectorId),
                DEFAULT_SUPPLY_CHAIN_TASK_CODE, bin(actorId), bin(actorId));
    }

    private void ensureDefaultProductMasterSyncTask(UUID tenantId, UUID actorId, UUID connectorId) {
        if (count("""
                SELECT COUNT(*) FROM integration_sync_task
                 WHERE tenant_id=? AND connector_id=? AND object_type='PRODUCT_MASTER_DATA'
                   AND deleted_at IS NULL
                """, bin(tenantId), bin(connectorId)) > 0) {
            return;
        }
        int restored = jdbc.update("""
                UPDATE integration_sync_task
                   SET object_type='PRODUCT_MASTER_DATA', task_status='IDLE', enabled=1,
                       deleted_at=NULL, deleted_by=NULL, delete_reason=NULL,
                       version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                 WHERE tenant_id=? AND connector_id=? AND task_code=? AND deleted_at IS NOT NULL
                """, bin(actorId), bin(tenantId), bin(connectorId), DEFAULT_PRODUCT_MASTER_TASK_CODE);
        if (restored == 1) return;

        jdbc.update("""
                INSERT INTO integration_sync_task
                    (id, tenant_id, connector_id, task_code, object_type, task_status,
                     next_run_at, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'PRODUCT_MASTER_DATA', 'IDLE', NULL,
                        UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                """, bin(UUID.randomUUID()), bin(tenantId), bin(connectorId),
                DEFAULT_PRODUCT_MASTER_TASK_CODE, bin(actorId), bin(actorId));
    }

    private void requireNoExistingOrderSyncTask(UUID tenantId, UUID connectorId, UUID ignoredTaskId) {
        String sql = ignoredTaskId == null
                ? """
                    SELECT COUNT(*) FROM integration_sync_task
                     WHERE tenant_id=? AND connector_id=? AND object_type='ORDER' AND deleted_at IS NULL
                    """
                : """
                    SELECT COUNT(*) FROM integration_sync_task
                     WHERE tenant_id=? AND connector_id=? AND object_type='ORDER'
                       AND id<>? AND deleted_at IS NULL
                    """;
        int count = ignoredTaskId == null
                ? count(sql, bin(tenantId), bin(connectorId))
                : count(sql, bin(tenantId), bin(connectorId), bin(ignoredTaskId));
        if (count > 0) {
            throw new IllegalArgumentException("每个订货宝连接器只允许一个ORDER同步任务，系统已自动创建默认任务");
        }
    }

    private static ConnectorView connector(ResultSet rs) throws SQLException {
        return new ConnectorView(IntegrationUuidCodec.decode(rs, "id"),
                IntegrationUuidCodec.decode(rs, "tenant_id"), rs.getString("connector_code"),
                rs.getString("connector_name"), rs.getString("base_url"), rs.getString("auth_secret_ref"),
                rs.getString("status"), rs.getLong("version"));
    }

    private static SyncTaskView syncTask(ResultSet rs) throws SQLException {
        return new SyncTaskView(IntegrationUuidCodec.decode(rs, "id"),
                IntegrationUuidCodec.decode(rs, "tenant_id"), IntegrationUuidCodec.decode(rs, "connector_id"),
                rs.getString("task_code"), rs.getString("object_type"), rs.getString("task_status"),
                instant(rs.getTimestamp("last_run_at")), instant(rs.getTimestamp("next_run_at")),
                rs.getLong("version"));
    }

    private static OrderMirrorView orderMirror(ResultSet rs) throws SQLException {
        return new OrderMirrorView(IntegrationUuidCodec.decode(rs, "id"),
                IntegrationUuidCodec.decode(rs, "tenant_id"), rs.getString("source_order_id"),
                rs.getString("order_no"), rs.getString("source_status"), rs.getBigDecimal("amount"),
                instant(rs.getTimestamp("order_time")), rs.getString("mirror_status"), rs.getLong("version"));
    }

    private static SyncLogView syncLog(ResultSet rs) throws SQLException {
        return new SyncLogView(IntegrationUuidCodec.decode(rs, "id"),
                IntegrationUuidCodec.decode(rs, "tenant_id"), IntegrationUuidCodec.decode(rs, "task_id"),
                IntegrationUuidCodec.decode(rs, "run_id"), rs.getString("log_level"), rs.getString("message"),
                rs.getString("error_code"), instant(rs.getTimestamp("occurred_at")));
    }

    private static FieldMappingView fieldMapping(ResultSet rs) throws SQLException {
        return new FieldMappingView(IntegrationUuidCodec.decode(rs, "id"),
                IntegrationUuidCodec.decode(rs, "tenant_id"), IntegrationUuidCodec.decode(rs, "connector_id"),
                rs.getString("source_field"), rs.getString("target_field"), rs.getString("transform_type"),
                rs.getBoolean("enabled"), rs.getLong("version"));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static byte[] bin(UUID value) { return IntegrationUuidCodec.encode(value); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static void requireChanged(int changed) {
        if (changed != 1) throw new IllegalStateException("Record changed concurrently or no longer exists");
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
        return required(value, "objectType").toUpperCase(java.util.Locale.ROOT);
    }
    private static String allowed(String value, Set<String> values, String field) {
        String normalized = required(value, field).toUpperCase();
        if (!values.contains(normalized)) throw new IllegalArgumentException("Invalid " + field);
        return normalized;
    }
    private static void validateConnector(ConnectorCommand c) {
        required(c.code(), "code");
        required(c.name(), "name");
        required(c.baseUrl(), "baseUrl");
        required(c.authSecretRef(), "authSecretRef");
    }
    private static void validateSyncTask(SyncTaskCommand c) {
        if (c.connectorId() == null) throw new IllegalArgumentException("connectorId is required");
        required(c.code(), "code"); required(c.objectType(), "objectType");
    }
    private static void validateFieldMapping(FieldMappingCommand c) {
        if (c.connectorId() == null) throw new IllegalArgumentException("connectorId is required");
        required(c.sourceField(), "sourceField"); required(c.targetField(), "targetField");
    }
}
