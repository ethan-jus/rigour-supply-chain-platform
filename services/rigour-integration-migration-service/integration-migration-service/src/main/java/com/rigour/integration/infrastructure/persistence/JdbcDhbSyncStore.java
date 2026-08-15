package com.rigour.integration.infrastructure.persistence;

import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 订货宝订单同步的 JDBC 持久化适配器。
 *
 * <p>外部订单每个新版本先进入 Raw Landing；只有同一事务中的镜像和 Outbox 写入成功后，
 * Raw Landing 才标记为 PROCESSED。重复的 payload 不重复产生镜像事件。</p>
 */
public final class JdbcDhbSyncStore implements DhbSyncStore {

    private static final String SOURCE_SYSTEM = "DHB";
    private static final String SOURCE_OBJECT_TYPE = "ORDER";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public JdbcDhbSyncStore(JdbcTemplate jdbc,
                                   PlatformTransactionManager transactionManager,
                                   ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public SyncTaskContext loadTask(UUID tenantId, UUID taskId) {
        return taskQuery(false, tenantId, taskId);
    }

    @Override
    public SyncCheckpoint loadCheckpoint(UUID tenantId, UUID taskId) {
        List<SyncCheckpoint> rows = jdbc.query("""
                SELECT cursor_type, cursor_value, source_updated_at, last_success_run_id
                  FROM integration_sync_checkpoint
                 WHERE tenant_id=? AND task_id=?
                """, (rs, row) -> new SyncCheckpoint(
                rs.getString("cursor_type"), rs.getString("cursor_value"),
                instant(rs.getTimestamp("source_updated_at")),
                IntegrationUuidCodec.decode(rs.getBytes("last_success_run_id"))),
                bin(tenantId), bin(taskId));
        return rows.isEmpty() ? null : rows.getFirst();
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
            jdbc.update("""
                    INSERT INTO integration_sync_run
                        (id, tenant_id, task_id, trigger_type, status, cursor_before,
                         window_from, window_to, started_at, created_at, created_by,
                         updated_at, updated_by)
                    VALUES (?, ?, ?, 'MANUAL', 'RUNNING', ?, ?, ?, UTC_TIMESTAMP(6),
                            UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(runId), bin(tenantId), bin(taskId), cursorBefore,
                    timestamp(windowFrom), timestamp(windowTo), bin(actorId), bin(actorId));
            int changed = jdbc.update("""
                    UPDATE integration_sync_task
                       SET task_status='RUNNING', last_run_at=UTC_TIMESTAMP(6),
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND task_status<>'RUNNING' AND deleted_at IS NULL
                    """, bin(actorId), bin(tenantId), bin(taskId));
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
            long accepted = 0;
            long duplicate = 0;
            long rejected = 0;
            Instant received = receivedAt == null ? Instant.now() : receivedAt;
            for (OrderSummary order : orders) {
                if (order == null || blank(order.sourceId()) || blank(order.orderNumber())) {
                    rejected++;
                    continue;
                }
                String payloadJson = writeJson(order.attributes());
                String payloadChecksum = sha256(payloadJson);
                UUID rawId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO integration_raw_landing
                            (id, tenant_id, connector_id, run_id, source_system,
                             source_object_type, source_id, source_version, source_updated_at,
                             payload_json, payload_checksum, received_at, landing_status,
                             attempts, last_attempt_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', 0, NULL)
                        ON DUPLICATE KEY UPDATE id=id
                        """, bin(rawId), bin(tenantId), bin(task.connectorId()), bin(runId),
                        SOURCE_SYSTEM, SOURCE_OBJECT_TYPE, order.sourceId(),
                        sourceVersion(order), timestamp(order.updatedAt()), payloadJson,
                        payloadChecksum, timestamp(received));
                UUID persistedRawId = findRawLanding(tenantId, order.sourceId(), payloadChecksum);
                if (persistedRawId == null) {
                    throw new IllegalStateException("订货宝 Raw Landing 写入后无法回读");
                }
                if (!rawId.equals(persistedRawId)) {
                    duplicate++;
                    continue;
                }

                UUID mirrorId = upsertOrderMirror(tenantId, order, rawId);
                appendOutbox(tenantId, mirrorId, rawId, order, payloadChecksum);
                jdbc.update("""
                        UPDATE integration_raw_landing
                           SET landing_status='PROCESSED', processed_at=UTC_TIMESTAMP(6),
                               attempts=attempts+1, last_attempt_at=UTC_TIMESTAMP(6)
                         WHERE tenant_id=? AND id=?
                        """, bin(tenantId), bin(rawId));
                accepted++;
            }
            return new PagePersistResult(accepted, duplicate, rejected);
        });
    }

    @Override
    public void finishRun(UUID tenantId, UUID actorId, UUID taskId, UUID runId,
                          Instant windowFrom, Instant windowTo, String status,
                          long fetchedCount, long acceptedCount, long duplicateCount,
                          long rejectedCount, String cursorAfter,
                          String errorCode, String errorMessage) {
        String normalizedStatus = normalizeRunStatus(status);
        String safeError = truncate(errorMessage);
        transaction.executeWithoutResult(transactionStatus -> {
            int changed = jdbc.update("""
                    UPDATE integration_sync_run
                       SET status=?, cursor_after=?, window_from=?, window_to=?,
                           fetched_count=?, accepted_count=?, duplicate_count=?,
                           rejected_count=?, finished_at=UTC_TIMESTAMP(6),
                           error_code=?, error_message=?, version=version+1,
                           updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND task_id=?
                    """, normalizedStatus,
                    "SUCCEEDED".equals(normalizedStatus) ? cursorAfter : null,
                    timestamp(windowFrom), timestamp(windowTo), fetchedCount, acceptedCount,
                    duplicateCount, rejectedCount, errorCode, safeError, bin(actorId),
                    bin(tenantId), bin(runId), bin(taskId));
            requireChanged(changed);

            String taskStatus = "SUCCEEDED".equals(normalizedStatus) ? "COMPLETED" : "FAILED";
            jdbc.update("""
                    UPDATE integration_sync_task
                       SET task_status=?, next_run_at=NULL, version=version+1,
                           updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND deleted_at IS NULL
                    """, taskStatus, bin(actorId), bin(tenantId), bin(taskId));

            if ("SUCCEEDED".equals(normalizedStatus)) {
                upsertCheckpoint(tenantId, taskId, runId, windowTo, cursorAfter, actorId);
            }
        });
    }

    private SyncTaskContext taskQuery(boolean forUpdate, UUID tenantId, UUID taskId) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        List<SyncTaskContext> rows = jdbc.query("""
                SELECT t.tenant_id, t.id, t.connector_id, t.task_code, t.object_type,
                       t.task_status, c.base_url, c.auth_secret_ref, c.status AS connector_status,
                       COALESCE(t.batch_size, 100) AS batch_size,
                       COALESCE(t.retry_limit, 3) AS retry_limit,
                       COALESCE(t.overlap_seconds, 300) AS overlap_seconds,
                       COALESCE(t.enabled, 1) AS enabled
                  FROM integration_sync_task t
                  JOIN integration_dhb_connector c
                    ON c.tenant_id=t.tenant_id AND c.id=t.connector_id
                 WHERE t.tenant_id=? AND t.id=?
                   AND t.deleted_at IS NULL AND c.deleted_at IS NULL
                """ + lock, (rs, row) -> new SyncTaskContext(
                IntegrationUuidCodec.decode(rs, "tenant_id"),
                IntegrationUuidCodec.decode(rs, "id"),
                IntegrationUuidCodec.decode(rs, "connector_id"),
                rs.getString("task_code"), rs.getString("object_type"),
                rs.getString("task_status"), rs.getString("base_url"),
                rs.getString("auth_secret_ref"), rs.getString("connector_status"),
                rs.getInt("batch_size"), rs.getInt("retry_limit"),
                rs.getInt("overlap_seconds"), rs.getBoolean("enabled")),
                bin(tenantId), bin(taskId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String checkpointValue(UUID tenantId, UUID taskId) {
        List<String> rows = jdbc.query("""
                SELECT cursor_value FROM integration_sync_checkpoint
                 WHERE tenant_id=? AND task_id=?
                """, (rs, row) -> rs.getString("cursor_value"), bin(tenantId), bin(taskId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private UUID upsertOrderMirror(UUID tenantId, OrderSummary order, UUID rawId) {
        UUID existing = findOrderMirror(tenantId, order.sourceId());
        BigDecimal amount = order.amount() == null ? BigDecimal.ZERO : order.amount();
        String orderNumber = required(order.orderNumber(), "orderNumber");
        if (existing == null) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO integration_order_mirror
                        (id, tenant_id, source_order_id, order_no, source_status, amount,
                         order_time, raw_landing_id, mirror_status, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, bin(id), bin(tenantId), order.sourceId(), orderNumber, order.status(), amount,
                    timestamp(order.createdAt()), bin(rawId));
            return id;
        }
        jdbc.update("""
                UPDATE integration_order_mirror
                   SET order_no=?, source_status=?, amount=?, order_time=?, raw_landing_id=?,
                       mirror_status='ACTIVE', version=version+1, updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND source_order_id=?
                """, orderNumber, order.status(), amount, timestamp(order.createdAt()), bin(rawId),
                bin(tenantId), order.sourceId());
        return existing;
    }

    private void appendOutbox(UUID tenantId, UUID mirrorId, UUID rawId,
                              OrderSummary order, String sourceChecksum) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceSystem", SOURCE_SYSTEM);
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
        String eventKey = "DHB_ORDER_MIRROR:" + order.sourceId() + ":" + sourceChecksum;
        jdbc.update("""
                INSERT INTO integration_outbox_event
                    (id, tenant_id, aggregate_type, aggregate_id, event_type, event_key,
                     payload_json, payload_checksum, status, attempts, available_at,
                     created_at, updated_at)
                VALUES (?, ?, 'ORDER', ?, 'DHB_ORDER_MIRROR_UPSERTED', ?, ?, ?,
                        'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE event_key=event_key
                """, bin(UUID.randomUUID()), bin(tenantId), bin(mirrorId), eventKey,
                payloadJson, sha256(payloadJson));
    }

    private void upsertCheckpoint(UUID tenantId, UUID taskId, UUID runId,
                                  Instant windowTo, String cursorAfter, UUID actorId) {
        UUID existing = findCheckpoint(tenantId, taskId);
        if (existing == null) {
            jdbc.update("""
                    INSERT INTO integration_sync_checkpoint
                        (id, tenant_id, task_id, cursor_type, cursor_value, source_updated_at,
                         last_success_run_id, version, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, 'TIME_WINDOW', ?, ?, ?, 0, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(UUID.randomUUID()), bin(tenantId), bin(taskId), cursorAfter,
                    timestamp(windowTo), bin(runId), bin(actorId), bin(actorId));
            return;
        }
        jdbc.update("""
                UPDATE integration_sync_checkpoint
                   SET cursor_type='TIME_WINDOW', cursor_value=?, source_updated_at=?,
                       last_success_run_id=?, version=version+1,
                       updated_at=UTC_TIMESTAMP(6), updated_by=?
                 WHERE tenant_id=? AND task_id=? AND id=?
                """, cursorAfter, timestamp(windowTo), bin(runId), bin(actorId),
                bin(tenantId), bin(taskId), bin(existing));
    }

    private UUID findOrderMirror(UUID tenantId, String sourceOrderId) {
        return findUuid("""
                SELECT id FROM integration_order_mirror
                 WHERE tenant_id=? AND source_order_id=?
                """, bin(tenantId), sourceOrderId);
    }

    private UUID findRawLanding(UUID tenantId, String sourceId, String payloadChecksum) {
        return findUuid("""
                SELECT id FROM integration_raw_landing
                 WHERE tenant_id=? AND source_system=? AND source_object_type=?
                   AND source_id=? AND payload_checksum=?
                """, bin(tenantId), SOURCE_SYSTEM, SOURCE_OBJECT_TYPE, sourceId, payloadChecksum);
    }

    private UUID findCheckpoint(UUID tenantId, UUID taskId) {
        return findUuid("""
                SELECT id FROM integration_sync_checkpoint
                 WHERE tenant_id=? AND task_id=?
                """, bin(tenantId), bin(taskId));
    }

    private UUID findUuid(String sql, Object... args) {
        List<UUID> rows = jdbc.query(sql, (rs, row) -> IntegrationUuidCodec.decode(rs, "id"),
                args);
        return rows.isEmpty() ? null : rows.getFirst();
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

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private static String string(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static byte[] bin(UUID value) {
        return IntegrationUuidCodec.encode(value);
    }

    private static String required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireChanged(int changed) {
        if (changed != 1) throw new IllegalStateException("同步批次状态已被其他执行者改变");
    }

    private static BusinessException alreadyRunning() {
        return new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                "相同租户和订货宝任务已有同步批次运行中", java.util.List.of());
    }
}
