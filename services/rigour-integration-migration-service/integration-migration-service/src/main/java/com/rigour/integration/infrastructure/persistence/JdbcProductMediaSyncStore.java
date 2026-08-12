package com.rigour.integration.infrastructure.persistence;

import com.rigour.integration.api.v1.model.DhbApiModels.ProductMediaSyncView;
import com.rigour.integration.application.port.out.DhbClient.Connector;
import com.rigour.integration.application.port.out.DhbClient.ProductImage;
import com.rigour.integration.application.port.out.ProductMediaSyncStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 商品图片异步任务 JDBC 适配器；通过行锁和 SKIP LOCKED 支持多实例消费者。 */
public final class JdbcProductMediaSyncStore implements ProductMediaSyncStore {
    private static final int TRANSACTION_RETRY_LIMIT = 4;
    private static final long TRANSACTION_RETRY_BACKOFF_MS = 25L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcProductMediaSyncStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public ProductMediaSyncView create(UUID tenantId, UUID actorId, UUID connectorId,
                                       long totalImages, List<MediaItem> items) {
        UUID jobId = UUID.randomUUID();
        executeWithLockRetry(status -> {
            jdbc.update("""
                    INSERT INTO integration_product_media_job
                        (id, tenant_id, connector_id, status, total_images,
                         created_at, created_by, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), 0)
                    """, bin(jobId), bin(tenantId), bin(connectorId),
                    items.isEmpty() ? "SUCCEEDED" : "QUEUED", totalImages, bin(actorId));
            for (MediaItem item : items) {
                ProductImage image = item.image();
                jdbc.update("""
                        INSERT INTO integration_product_media_item
                            (id, job_id, tenant_id, connector_id, source_product_id,
                             source_resource_id, source_goods_id, source_url, original_name,
                             source_file_name, sort_order, status, object_key, content_type,
                             created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0)
                        """, bin(UUID.randomUUID()), bin(jobId), bin(tenantId), bin(connectorId),
                        item.sourceProductId(), image.sourceResourceId(), image.sourceGoodsId(),
                        image.sourceUrl(), image.originalName(), image.fileName(), image.sortOrder(),
                        item.reused() ? "SUCCEEDED" : "PENDING", item.reusableObjectKey(),
                        item.reusableContentType());
            }
            if (items.isEmpty()) {
                jdbc.update("""
                        UPDATE integration_product_media_job
                           SET finished_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
                         WHERE id=?
                        """, bin(jobId));
            }
            return null;
        });
        return status(tenantId, connectorId, jobId);
    }

    @Override
    public ProductMediaSyncView status(UUID tenantId, UUID connectorId, UUID jobId) {
        refreshJob(jobId, tenantId, connectorId);
        List<ProductMediaSyncView> rows = jdbc.query("""
                SELECT id, connector_id, status, total_images, completed_images, failed_images,
                       created_at, started_at, finished_at, error_code, error_message
                  FROM integration_product_media_job
                 WHERE tenant_id=? AND connector_id=? AND id=?
                """, JOB_VIEW, bin(tenantId), bin(connectorId), bin(jobId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("商品图片同步任务不存在");
        }
        return rows.getFirst();
    }

    @Override
    public ReusableMedia findReusable(UUID tenantId, UUID connectorId, String sourceProductId,
                                      ProductImage image) {
        if (tenantId == null || connectorId == null || sourceProductId == null || image == null) {
            return null;
        }
        List<ReusableMedia> rows = jdbc.query("""
                SELECT object_key, content_type
                  FROM integration_product_media_item
                 WHERE tenant_id=? AND connector_id=? AND status='SUCCEEDED'
                   AND object_key IS NOT NULL
                   AND source_product_id=?
                   AND source_resource_id <=> ?
                   AND source_goods_id <=> ?
                   AND source_url <=> ?
                   AND original_name <=> ?
                   AND source_file_name <=> ?
                   AND sort_order <=> ?
                 ORDER BY updated_at DESC, id DESC
                 LIMIT 1
                """, (rs, row) -> new ReusableMedia(rs.getString("object_key"),
                        rs.getString("content_type")), bin(tenantId), bin(connectorId), sourceProductId,
                image.sourceResourceId(), image.sourceGoodsId(), image.sourceUrl(), image.originalName(),
                image.fileName(), image.sortOrder());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public String completedObjectKey(UUID tenantId, UUID connectorId, UUID jobId,
                                    String sourceProductId, String sourceResourceId,
                                    Integer sortOrder) {
        if (sourceResourceId != null && !sourceResourceId.isBlank()) {
            List<String> rows = jdbc.query("""
                    SELECT object_key
                      FROM integration_product_media_item
                     WHERE tenant_id=? AND connector_id=? AND job_id=?
                       AND source_product_id=? AND source_resource_id=?
                       AND status='SUCCEEDED' AND object_key IS NOT NULL
                     ORDER BY id
                     LIMIT 1
                    """, (rs, row) -> rs.getString(1), bin(tenantId), bin(connectorId),
                    bin(jobId), sourceProductId, sourceResourceId);
            return rows.isEmpty() ? null : rows.getFirst();
        }
        List<String> rows = jdbc.query("""
                SELECT object_key
                  FROM integration_product_media_item
                 WHERE tenant_id=? AND connector_id=? AND job_id=?
                   AND source_product_id=? AND source_resource_id IS NULL
                   AND sort_order <=> ? AND status='SUCCEEDED' AND object_key IS NOT NULL
                 ORDER BY id
                 LIMIT 1
                """, (rs, row) -> rs.getString(1), bin(tenantId), bin(connectorId),
                bin(jobId), sourceProductId, sortOrder);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public List<ClaimedMediaItem> claimPending(int limit, int maxAttempts) {
        return executeWithLockRetry(status -> {
            jdbc.update("""
                    UPDATE integration_product_media_item
                       SET status='PENDING', updated_at=UTC_TIMESTAMP(6), version=version+1
                     WHERE status='RUNNING'
                       AND updated_at < TIMESTAMPADD(MINUTE, -10, UTC_TIMESTAMP(6))
                    """);
            List<ClaimedMediaItem> items = jdbc.query("""
                    SELECT i.id, i.job_id, i.tenant_id, i.connector_id,
                           c.base_url, c.auth_secret_ref,
                           i.source_product_id, i.source_resource_id, i.source_goods_id,
                           i.source_url, i.original_name, i.source_file_name, i.sort_order,
                           i.attempts
                      FROM integration_product_media_item i
                      JOIN integration_product_media_job j ON j.id=i.job_id
                      JOIN integration_dhb_connector c ON c.id=i.connector_id
                     WHERE i.status='PENDING'
                       AND (i.next_retry_at IS NULL OR i.next_retry_at <= UTC_TIMESTAMP(6))
                       AND i.attempts < ?
                       AND j.status NOT IN ('SUCCEEDED', 'FAILED')
                       AND c.status='ACTIVE'
                     ORDER BY i.created_at, i.id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                    """, CLAIMED_ITEM, maxAttempts, limit);
            for (ClaimedMediaItem item : items) {
                jdbc.update("""
                        UPDATE integration_product_media_item
                           SET status='RUNNING', attempts=attempts+1,
                               updated_at=UTC_TIMESTAMP(6), version=version+1
                         WHERE id=? AND status='PENDING'
                        """, bin(item.itemId()));
            }
            return items;
        });
    }

    @Override
    public void markSucceeded(UUID itemId, String objectKey, String contentType) {
        UUID jobId = jobId(itemId);
        executeWithLockRetry(status -> {
            int changed = jdbc.update("""
                    UPDATE integration_product_media_item
                       SET status='SUCCEEDED', object_key=?, content_type=?,
                           error_code=NULL, error_message=NULL, next_retry_at=NULL,
                           updated_at=UTC_TIMESTAMP(6), version=version+1
                     WHERE id=? AND status='RUNNING'
            """, objectKey, contentType, bin(itemId));
            requireChanged(changed, "图片上传任务已被其他消费者处理");
            return null;
        });
        refreshJobAfterItemChange(jobId);
    }

    @Override
    public void markFailed(UUID itemId, String errorCode, String errorMessage,
                           boolean retryable, int maxAttempts) {
        UUID jobId = jobId(itemId);
        executeWithLockRetry(status -> {
            String state = retryable ? "PENDING" : "FAILED";
            jdbc.update("""
                    UPDATE integration_product_media_item
                       SET status=CASE WHEN attempts < ? AND ?='PENDING' THEN 'PENDING' ELSE 'FAILED' END,
                           next_retry_at=CASE WHEN attempts < ? AND ?='PENDING'
                                              THEN TIMESTAMPADD(SECOND, 5, UTC_TIMESTAMP(6))
                                              ELSE NULL END,
                           error_code=?, error_message=?, updated_at=UTC_TIMESTAMP(6), version=version+1
                     WHERE id=? AND status='RUNNING'
                    """, maxAttempts, state, maxAttempts, state,
                    errorCode, truncate(errorMessage), bin(itemId));
            return null;
        });
        refreshJobAfterItemChange(jobId);
    }

    private void refreshJob(UUID jobId, UUID tenantId, UUID connectorId) {
        executeWithLockRetry(status -> {
            int exists = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM integration_product_media_job
                     WHERE id=? AND tenant_id=? AND connector_id=?
                    """, Integer.class, bin(jobId), bin(tenantId), bin(connectorId));
            if (exists == 0) {
                throw new IllegalArgumentException("商品图片同步任务不存在");
            }
            refreshJobInTransaction(jobId);
            return null;
        });
    }

    private void refreshJobAfterItemChange(UUID jobId) {
        executeWithLockRetry(status -> {
            refreshJobInTransaction(jobId);
            return null;
        });
    }

    private void refreshJobInTransaction(UUID jobId) {
        List<JobCounts> rows = jdbc.query("""
                SELECT j.id,
                       j.total_images,
                       COALESCE(SUM(CASE WHEN i.status='SUCCEEDED' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN i.status='FAILED' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN i.status IN ('PENDING','RUNNING') THEN 1 ELSE 0 END), 0)
                  FROM integration_product_media_job j
                  LEFT JOIN integration_product_media_item i ON i.job_id=j.id
                 WHERE j.id=?
                 GROUP BY j.id, j.total_images
                """, (rs, row) -> new JobCounts(rs.getBytes(1), rs.getLong(2),
                rs.getLong(3), rs.getLong(4), rs.getLong(5)), bin(jobId));
        if (rows.isEmpty()) return;
        JobCounts counts = rows.getFirst();
        String nextStatus = counts.pending() == 0
                ? (counts.failed() == 0 ? "SUCCEEDED" : "FAILED") : "RUNNING";
        String errorCode = null;
        String errorMessage = null;
        if ("FAILED".equals(nextStatus)) {
            List<String[]> errors = jdbc.query("""
                    SELECT error_code, error_message
                      FROM integration_product_media_item
                     WHERE job_id=? AND status='FAILED'
                     ORDER BY updated_at DESC, id DESC
                     LIMIT 1
                    """, (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, counts.id());
            if (!errors.isEmpty()) {
                errorCode = errors.getFirst()[0];
                errorMessage = errors.getFirst()[1];
            }
        }
        jdbc.update("""
                UPDATE integration_product_media_job
                   SET status=?,
                       started_at=CASE WHEN started_at IS NULL AND ? <> 'QUEUED'
                                       THEN UTC_TIMESTAMP(6) ELSE started_at END,
                       completed_images=?, failed_images=?,
                       error_code=?, error_message=?,
                       finished_at=CASE WHEN ? IN ('SUCCEEDED','FAILED')
                                        THEN COALESCE(finished_at, UTC_TIMESTAMP(6)) ELSE NULL END,
                       updated_at=UTC_TIMESTAMP(6), version=version+1
                 WHERE id=?
                """, nextStatus, nextStatus, counts.completed(), counts.failed(), errorCode, errorMessage,
                nextStatus, counts.id());
    }

    private UUID jobId(UUID itemId) {
        List<UUID> rows = jdbc.query("""
                SELECT job_id
                  FROM integration_product_media_item
                 WHERE id=?
                """, (rs, row) -> IntegrationUuidCodec.decode(rs.getBytes(1)), bin(itemId));
        if (rows.isEmpty()) throw new IllegalStateException("商品图片任务明细不存在");
        return rows.getFirst();
    }

    /**
     * 商品图片明细共享同一个批次汇总行；高并发完成时 MySQL 可能选择回滚其中一个事务。
     * 对明确的锁冲突重新开启完整事务，避免在原事务中继续使用已回滚的连接状态。
     */
    private <T> T executeWithLockRetry(TransactionCallback<T> callback) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= TRANSACTION_RETRY_LIMIT; attempt++) {
            try {
                return transaction.execute(callback);
            } catch (CannotAcquireLockException | DeadlockLoserDataAccessException error) {
                last = error;
                if (attempt == TRANSACTION_RETRY_LIMIT) throw error;
                try {
                    Thread.sleep(TRANSACTION_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
            }
        }
        throw last == null ? new IllegalStateException("商品图片事务执行失败") : last;
    }

    private static final RowMapper<ProductMediaSyncView> JOB_VIEW = (rs, row) ->
            new ProductMediaSyncView(IntegrationUuidCodec.decode(rs.getBytes("id")),
                    IntegrationUuidCodec.decode(rs.getBytes("connector_id")),
                    rs.getString("status"), rs.getLong("total_images"),
                    rs.getLong("completed_images"), rs.getLong("failed_images"),
                    instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                    instant(rs.getTimestamp("finished_at")), rs.getString("error_code"),
                    rs.getString("error_message"));

    private static final RowMapper<ClaimedMediaItem> CLAIMED_ITEM = (rs, row) -> {
        UUID tenantId = IntegrationUuidCodec.decode(rs.getBytes("tenant_id"));
        UUID connectorId = IntegrationUuidCodec.decode(rs.getBytes("connector_id"));
        return new ClaimedMediaItem(
                IntegrationUuidCodec.decode(rs.getBytes("id")),
                IntegrationUuidCodec.decode(rs.getBytes("job_id")), tenantId, connectorId,
                new Connector(tenantId, connectorId, rs.getString("base_url"),
                        rs.getString("auth_secret_ref")),
                rs.getString("source_product_id"),
                new ProductImage(rs.getString("source_resource_id"), rs.getString("source_goods_id"),
                        rs.getString("original_name"), rs.getString("source_file_name"),
                        (Integer) rs.getObject("sort_order"), rs.getString("source_url")),
                rs.getInt("attempts"));
    };

    private static byte[] bin(UUID value) {
        return IntegrationUuidCodec.encode(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String value) {
        if (value == null) return null;
        String oneLine = value.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() > 2000 ? oneLine.substring(0, 2000) : oneLine;
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw new IllegalStateException(message);
    }

    private record JobCounts(byte[] id, long total, long completed, long failed, long pending) {
    }
}
