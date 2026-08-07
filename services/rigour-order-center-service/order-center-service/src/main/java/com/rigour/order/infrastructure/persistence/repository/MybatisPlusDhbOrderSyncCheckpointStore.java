package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.order.application.port.out.DhbOrderSyncCheckpointStore;
import com.rigour.order.infrastructure.persistence.entity.DhbOrderSyncCheckpointEntity;
import com.rigour.order.infrastructure.persistence.mapper.DhbOrderSyncCheckpointMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用MyBatis-Plus保存订货宝订单域增量同步游标。 */
@Repository
public class MybatisPlusDhbOrderSyncCheckpointStore implements DhbOrderSyncCheckpointStore {
    private static final String ORDER = "ORDER";
    private final DhbOrderSyncCheckpointMapper mapper;
    private final Clock clock;

    public MybatisPlusDhbOrderSyncCheckpointStore(DhbOrderSyncCheckpointMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Instant lastSuccessAt(String tenantId, UUID connectorId, String objectType) {
        DhbOrderSyncCheckpointEntity entity = find(tenantId, connectorId, objectType);
        return entity == null || entity.lastSuccessAt == null
                ? null : entity.lastSuccessAt.toInstant(ZoneOffset.UTC);
    }

    @Override
    @Transactional
    public void markSucceeded(String tenantId, UUID connectorId, String objectType,
                              UUID runId, Instant windowTo) {
        requireKey(tenantId, connectorId, objectType);
        if (windowTo == null) throw new IllegalArgumentException("windowTo不能为空");
        LocalDateTime now = LocalDateTime.now(clock);
        DhbOrderSyncCheckpointEntity entity = getOrCreate(tenantId, connectorId, objectType, now);
        entity.lastSuccessAt = LocalDateTime.ofInstant(windowTo, ZoneOffset.UTC);
        entity.lastRunId = runId == null ? null : runId.toString();
        entity.syncStatus = "SUCCEEDED";
        entity.lastRunAt = now;
        entity.lastError = null;
        entity.updatedAt = now;
        mapper.updateById(entity);
    }

    @Override
    @Transactional
    public void markFailed(String tenantId, UUID connectorId, String objectType,
                           UUID runId, String errorMessage) {
        requireKey(tenantId, connectorId, objectType);
        LocalDateTime now = LocalDateTime.now(clock);
        DhbOrderSyncCheckpointEntity entity = getOrCreate(tenantId, connectorId, objectType, now);
        entity.lastRunId = runId == null ? null : runId.toString();
        entity.syncStatus = "FAILED";
        entity.lastRunAt = now;
        entity.lastError = safeError(errorMessage);
        entity.updatedAt = now;
        mapper.updateById(entity);
    }

    private DhbOrderSyncCheckpointEntity find(String tenantId, UUID connectorId, String objectType) {
        requireKey(tenantId, connectorId, objectType);
        return mapper.selectOne(Wrappers.<DhbOrderSyncCheckpointEntity>query()
                .eq("tenant_id", tenantId)
                .eq("connector_id", connectorId.toString())
                .eq("object_type", objectType)
                .last("LIMIT 1"));
    }

    private DhbOrderSyncCheckpointEntity getOrCreate(String tenantId, UUID connectorId,
                                                      String objectType, LocalDateTime now) {
        DhbOrderSyncCheckpointEntity existing = find(tenantId, connectorId, objectType);
        if (existing != null) return existing;
        DhbOrderSyncCheckpointEntity entity = new DhbOrderSyncCheckpointEntity();
        entity.id = UUID.randomUUID().toString();
        entity.tenantId = tenantId;
        entity.connectorId = connectorId.toString();
        entity.objectType = objectType;
        entity.syncStatus = "IDLE";
        entity.createdAt = now;
        entity.updatedAt = now;
        mapper.insert(entity);
        return entity;
    }

    private static void requireKey(String tenantId, UUID connectorId, String objectType) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId不能为空");
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        if (objectType == null || objectType.isBlank()) throw new IllegalArgumentException("objectType不能为空");
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("(?i)(token|password|skey|secret|authorization)\\s*[=:：]\\s*[^,;，； ]+", "$1=[REDACTED]");
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }
}
