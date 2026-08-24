package com.rigour.integration.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductMediaSyncView;
import com.rigour.integration.application.port.out.DhbClient.Connector;
import com.rigour.integration.application.port.out.DhbClient.ProductImage;
import com.rigour.integration.application.port.out.ProductMediaSyncStore;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.DhbConnectorEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationProductMediaItemEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationProductMediaJobEntity;
import com.rigour.integration.infrastructure.persistence.mapper.DhbConnectorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationProductMediaItemMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationProductMediaJobMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 商品图片异步任务 MyBatis-Plus 仓储。 */
public class MybatisPlusProductMediaSyncStore implements ProductMediaSyncStore {
    private static final int TRANSACTION_RETRY_LIMIT = 4;
    private static final long TRANSACTION_RETRY_BACKOFF_MS = 25L;

    private final IntegrationProductMediaJobMapper jobMapper;
    private final IntegrationProductMediaItemMapper itemMapper;
    private final DhbConnectorMapper connectorMapper;
    private final TransactionTemplate transaction;

    public MybatisPlusProductMediaSyncStore(
            IntegrationProductMediaJobMapper jobMapper,
            IntegrationProductMediaItemMapper itemMapper,
            DhbConnectorMapper connectorMapper,
            PlatformTransactionManager transactionManager) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.connectorMapper = connectorMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public ProductMediaSyncView create(UUID tenantId, UUID actorId, UUID connectorId,
                                       long totalImages, List<MediaItem> items) {
        UUID jobId = UUID.randomUUID();
        List<MediaItem> safeItems = items == null ? List.of() : items;
        executeWithLockRetry(status -> {
            LocalDateTime now = now();
            IntegrationProductMediaJobEntity job = new IntegrationProductMediaJobEntity();
            job.id = bin(jobId);
            job.tenantId = bin(tenantId);
            job.connectorId = bin(connectorId);
            job.status = safeItems.isEmpty() ? "SUCCEEDED" : "QUEUED";
            job.totalImages = totalImages;
            job.completedImages = safeItems.stream().filter(MediaItem::reused).count();
            job.failedImages = 0L;
            job.createdAt = now;
            job.createdBy = bin(actorId);
            job.startedAt = safeItems.isEmpty() ? now : null;
            job.finishedAt = safeItems.isEmpty() ? now : null;
            job.updatedAt = now;
            job.version = 0L;
            jobMapper.insert(job);

            for (MediaItem item : safeItems) {
                ProductImage image = item.image();
                IntegrationProductMediaItemEntity row = new IntegrationProductMediaItemEntity();
                row.id = bin(UUID.randomUUID());
                row.jobId = bin(jobId);
                row.tenantId = bin(tenantId);
                row.connectorId = bin(connectorId);
                row.sourceProductId = item.sourceProductId();
                row.sourceResourceId = image.sourceResourceId();
                row.sourceGoodsId = image.sourceGoodsId();
                row.sourceUrl = image.sourceUrl();
                row.originalName = image.originalName();
                row.sourceFileName = image.fileName();
                row.sortOrder = image.sortOrder();
                row.status = item.reused() ? "SUCCEEDED" : "PENDING";
                row.objectKey = item.reusableObjectKey();
                row.contentType = item.reusableContentType();
                row.attempts = 0;
                row.createdAt = now;
                row.updatedAt = now;
                row.version = 0L;
                itemMapper.insert(row);
            }
            if (!safeItems.isEmpty()) refreshJobInTransaction(jobId);
            return null;
        });
        return status(tenantId, connectorId, jobId);
    }

    @Override
    public ProductMediaSyncView status(UUID tenantId, UUID connectorId, UUID jobId) {
        refreshJob(jobId, tenantId, connectorId);
        IntegrationProductMediaJobEntity row = job(jobId, tenantId, connectorId);
        if (row == null) {
            throw new IllegalArgumentException("商品图片同步任务不存在");
        }
        return view(row);
    }

    @Override
    public ReusableMedia findReusable(UUID tenantId, UUID connectorId, String sourceProductId,
                                      ProductImage image) {
        if (tenantId == null || connectorId == null || sourceProductId == null || image == null) {
            return null;
        }
        QueryWrapper<IntegrationProductMediaItemEntity> query =
                Wrappers.<IntegrationProductMediaItemEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("connector_id", bin(connectorId))
                        .eq("status", "SUCCEEDED")
                        .isNotNull("object_key")
                        .eq("source_product_id", sourceProductId)
                        .orderByDesc("updated_at", "id")
                        .last("LIMIT 1");
        nullSafeEq(query, "source_resource_id", image.sourceResourceId());
        nullSafeEq(query, "source_goods_id", image.sourceGoodsId());
        nullSafeEq(query, "source_url", image.sourceUrl());
        nullSafeEq(query, "original_name", image.originalName());
        nullSafeEq(query, "source_file_name", image.fileName());
        nullSafeEq(query, "sort_order", image.sortOrder());
        IntegrationProductMediaItemEntity row = first(itemMapper.selectList(query));
        return row == null ? null : new ReusableMedia(row.objectKey, row.contentType);
    }

    @Override
    public String completedObjectKey(UUID tenantId, UUID connectorId, UUID jobId,
                                     String sourceProductId, String sourceResourceId,
                                     Integer sortOrder) {
        QueryWrapper<IntegrationProductMediaItemEntity> query =
                Wrappers.<IntegrationProductMediaItemEntity>query()
                        .select("object_key")
                        .eq("tenant_id", bin(tenantId))
                        .eq("connector_id", bin(connectorId))
                        .eq("job_id", bin(jobId))
                        .eq("source_product_id", sourceProductId)
                        .eq("status", "SUCCEEDED")
                        .isNotNull("object_key")
                        .orderByAsc("id")
                        .last("LIMIT 1");
        if (sourceResourceId != null && !sourceResourceId.isBlank()) {
            query.eq("source_resource_id", sourceResourceId);
        } else {
            query.isNull("source_resource_id");
            nullSafeEq(query, "sort_order", sortOrder);
        }
        IntegrationProductMediaItemEntity row = first(itemMapper.selectList(query));
        return row == null ? null : row.objectKey;
    }

    @Override
    public List<ClaimedMediaItem> claimPending(int limit, int maxAttempts) {
        int safeLimit = Math.max(0, Math.min(limit, 100));
        if (safeLimit == 0) return List.of();
        return executeWithLockRetry(status -> {
            LocalDateTime now = now();
            itemMapper.update(null, Wrappers.<IntegrationProductMediaItemEntity>update()
                    .set("status", "PENDING")
                    .set("updated_at", now)
                    .setSql("version=version+1")
                    .eq("status", "RUNNING")
                    .lt("updated_at", now.minusMinutes(10)));

            QueryWrapper<IntegrationProductMediaItemEntity> query =
                    Wrappers.<IntegrationProductMediaItemEntity>query()
                            .eq("status", "PENDING")
                            .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", now))
                            .lt("attempts", maxAttempts)
                            .orderByAsc("created_at", "id")
                            .last("LIMIT " + (safeLimit * 5));
            List<ClaimedMediaItem> claimed = new ArrayList<>();
            for (IntegrationProductMediaItemEntity item : itemMapper.selectList(query)) {
                if (claimed.size() >= safeLimit) break;
                IntegrationProductMediaJobEntity job = job(IntegrationUuidCodec.decode(item.jobId),
                        IntegrationUuidCodec.decode(item.tenantId),
                        IntegrationUuidCodec.decode(item.connectorId));
                if (job == null || Set.of("SUCCEEDED", "FAILED").contains(job.status)) continue;
                DhbConnectorEntity connector = connector(item.tenantId, item.connectorId);
                if (connector == null || !"ACTIVE".equals(connector.status)) continue;
                int changed = itemMapper.update(null, Wrappers.<IntegrationProductMediaItemEntity>update()
                        .set("status", "RUNNING")
                        .set("updated_at", now)
                        .setSql("attempts=attempts+1")
                        .setSql("version=version+1")
                        .eq("id", item.id)
                        .eq("status", "PENDING"));
                if (changed == 1) {
                    item.attempts = item.attempts == null ? 1 : item.attempts + 1;
                    claimed.add(claimed(item, connector));
                }
            }
            return claimed;
        });
    }

    @Override
    public void markSucceeded(UUID itemId, String objectKey, String contentType) {
        UUID jobId = jobId(itemId);
        executeWithLockRetry(status -> {
            int changed = itemMapper.update(null, Wrappers.<IntegrationProductMediaItemEntity>update()
                    .set("status", "SUCCEEDED")
                    .set("object_key", objectKey)
                    .set("content_type", contentType)
                    .set("error_code", null)
                    .set("error_message", null)
                    .set("next_retry_at", null)
                    .set("updated_at", now())
                    .setSql("version=version+1")
                    .eq("id", bin(itemId))
                    .eq("status", "RUNNING"));
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
            IntegrationProductMediaItemEntity item = item(itemId);
            if (item == null || !"RUNNING".equals(item.status)) return null;
            boolean shouldRetry = retryable && item.attempts != null && item.attempts < maxAttempts;
            itemMapper.update(null, Wrappers.<IntegrationProductMediaItemEntity>update()
                    .set("status", shouldRetry ? "PENDING" : "FAILED")
                    .set("next_retry_at", shouldRetry ? now().plusSeconds(5) : null)
                    .set("error_code", errorCode)
                    .set("error_message", truncate(errorMessage))
                    .set("updated_at", now())
                    .setSql("version=version+1")
                    .eq("id", bin(itemId))
                    .eq("status", "RUNNING"));
            return null;
        });
        refreshJobAfterItemChange(jobId);
    }

    private void refreshJob(UUID jobId, UUID tenantId, UUID connectorId) {
        executeWithLockRetry(status -> {
            if (job(jobId, tenantId, connectorId) == null) {
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
        IntegrationProductMediaJobEntity job = job(jobId, null, null);
        if (job == null) return;
        List<IntegrationProductMediaItemEntity> items = itemMapper.selectList(
                Wrappers.<IntegrationProductMediaItemEntity>query().eq("job_id", bin(jobId)));
        long completed = items.stream().filter(item -> "SUCCEEDED".equals(item.status)).count();
        long failed = items.stream().filter(item -> "FAILED".equals(item.status)).count();
        long pending = items.stream().filter(item -> Set.of("PENDING", "RUNNING").contains(item.status)).count();
        String nextStatus = pending == 0 ? (failed == 0 ? "SUCCEEDED" : "FAILED") : "RUNNING";
        IntegrationProductMediaItemEntity lastError = items.stream()
                .filter(item -> "FAILED".equals(item.status))
                .sorted((a, b) -> nullLast(b.updatedAt).compareTo(nullLast(a.updatedAt)))
                .findFirst().orElse(null);
        jobMapper.update(null, Wrappers.<IntegrationProductMediaJobEntity>update()
                .set("status", nextStatus)
                .set("started_at", job.startedAt == null && !"QUEUED".equals(nextStatus) ? now() : job.startedAt)
                .set("completed_images", completed)
                .set("failed_images", failed)
                .set("error_code", "FAILED".equals(nextStatus) && lastError != null ? lastError.errorCode : null)
                .set("error_message", "FAILED".equals(nextStatus) && lastError != null ? lastError.errorMessage : null)
                .set("finished_at", Set.of("SUCCEEDED", "FAILED").contains(nextStatus)
                        ? (job.finishedAt == null ? now() : job.finishedAt) : null)
                .set("updated_at", now())
                .setSql("version=version+1")
                .eq("id", bin(jobId)));
    }

    private UUID jobId(UUID itemId) {
        IntegrationProductMediaItemEntity item = item(itemId);
        if (item == null) throw new IllegalStateException("商品图片任务明细不存在");
        return IntegrationUuidCodec.decode(item.jobId);
    }

    private IntegrationProductMediaItemEntity item(UUID itemId) {
        return itemId == null ? null : itemMapper.selectById(bin(itemId));
    }

    private IntegrationProductMediaJobEntity job(UUID jobId, UUID tenantId, UUID connectorId) {
        if (jobId == null) return null;
        QueryWrapper<IntegrationProductMediaJobEntity> query =
                Wrappers.<IntegrationProductMediaJobEntity>query()
                        .eq("id", bin(jobId))
                        .eq(tenantId != null, "tenant_id", bin(tenantId))
                        .eq(connectorId != null, "connector_id", bin(connectorId))
                        .last("LIMIT 1");
        return first(jobMapper.selectList(query));
    }

    private DhbConnectorEntity connector(byte[] tenantId, byte[] connectorId) {
        return first(connectorMapper.selectList(Wrappers.<DhbConnectorEntity>query()
                .eq("tenant_id", tenantId)
                .eq("id", connectorId)
                .isNull("deleted_at")
                .last("LIMIT 1")));
    }

    private ClaimedMediaItem claimed(IntegrationProductMediaItemEntity item,
                                     DhbConnectorEntity connector) {
        UUID tenantId = IntegrationUuidCodec.decode(item.tenantId);
        UUID connectorId = IntegrationUuidCodec.decode(item.connectorId);
        return new ClaimedMediaItem(IntegrationUuidCodec.decode(item.id),
                IntegrationUuidCodec.decode(item.jobId), tenantId, connectorId,
                new Connector(tenantId, connectorId, connector.baseUrl, connector.authSecretRef),
                item.sourceProductId,
                new ProductImage(item.sourceResourceId, item.sourceGoodsId, item.originalName,
                        item.sourceFileName, item.sortOrder, item.sourceUrl),
                item.attempts == null ? 0 : item.attempts);
    }

    private ProductMediaSyncView view(IntegrationProductMediaJobEntity row) {
        return new ProductMediaSyncView(IntegrationUuidCodec.decode(row.id),
                IntegrationUuidCodec.decode(row.connectorId), row.status, zero(row.totalImages),
                zero(row.completedImages), zero(row.failedImages), instant(row.createdAt),
                instant(row.startedAt), instant(row.finishedAt), row.errorCode, row.errorMessage);
    }

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

    private static <T> void nullSafeEq(QueryWrapper<T> query, String column, Object value) {
        if (value == null) query.isNull(column);
        else query.eq(column, value);
    }

    private static <T> T first(List<T> rows) {
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private static byte[] bin(UUID value) {
        return IntegrationUuidCodec.encode(value);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static java.time.Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime nullLast(LocalDateTime value) {
        return value == null ? LocalDateTime.MIN : value;
    }

    private static long zero(Long value) {
        return value == null ? 0L : value;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        String oneLine = value.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() > 2000 ? oneLine.substring(0, 2000) : oneLine;
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw new IllegalStateException(message);
    }
}
