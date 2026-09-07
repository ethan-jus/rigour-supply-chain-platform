package com.rigour.integration.infrastructure.lease;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.integration.application.port.out.DhbOrchestrationLease;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationDhbOrchestrationLeaseEntity;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationDhbOrchestrationLeaseMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** MyBatis-Plus实现的订货宝统一编排分布式租约。 */
@Component
public final class DhbOrchestrationLeaseManager implements DhbOrchestrationLease, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DhbOrchestrationLeaseManager.class);

    private final IntegrationDhbOrchestrationLeaseMapper mapper;
    private final DhbConnectorLeaseProperties properties;
    private final ScheduledExecutorService heartbeatExecutor;

    public DhbOrchestrationLeaseManager(IntegrationDhbOrchestrationLeaseMapper mapper,
                                        DhbConnectorLeaseProperties properties) {
        this.mapper = Objects.requireNonNull(mapper, "mapper不能为空");
        this.properties = Objects.requireNonNull(properties, "properties不能为空");
        properties.validate();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dhb-orchestration-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public <T> T execute(UUID tenantId, UUID connectorId, String ownerId,
                         java.util.function.Supplier<T> action) {
        Objects.requireNonNull(action, "action不能为空");
        Lease lease = acquire(tenantId, connectorId, ownerId);
        AtomicReference<Instant> expiresAt = new AtomicReference<>(lease.expiresAt());
        AtomicBoolean lost = new AtomicBoolean(false);
        long heartbeatSeconds = Math.max(5L, properties.getTtl().toSeconds() / 3L);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                expiresAt.set(renew(tenantId, connectorId, lease.token()).expiresAt());
            } catch (RuntimeException error) {
                if (!Instant.now().isBefore(expiresAt.get())) lost.set(true);
                log.warn("订货宝统一编排租约续租异常 tenantId={} connectorId={} ownerId={} expired={} errorType={}",
                        tenantId, connectorId, ownerId, lost.get(), error.getClass().getSimpleName());
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        try {
            T result = action.get();
            if (lost.get() || !Instant.now().isBefore(expiresAt.get())) {
                throw lost();
            }
            return result;
        } finally {
            heartbeat.cancel(false);
            release(tenantId, connectorId, lease.token());
        }
    }

    @jakarta.annotation.PreDestroy
    @Override
    public void close() {
        heartbeatExecutor.shutdownNow();
    }

    private Lease acquire(UUID tenantId, UUID connectorId, String ownerId) {
        require(tenantId, connectorId);
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId不能为空");
        }
        byte[] tenant = IntegrationUuidCodec.encode(tenantId);
        byte[] connector = IntegrationUuidCodec.encode(connectorId);
        LocalDateTime now = now();
        mapper.delete(Wrappers.<IntegrationDhbOrchestrationLeaseEntity>query()
                .eq("tenant_id", tenant)
                .eq("connector_id", connector)
                .le("expires_at", now));
        LocalDateTime leaseExpiresAt = now.plus(properties.getTtl());
        Lease lease = new Lease(UUID.randomUUID().toString(), leaseExpiresAt.toInstant(ZoneOffset.UTC));
        try {
            IntegrationDhbOrchestrationLeaseEntity row = new IntegrationDhbOrchestrationLeaseEntity();
            row.id = IntegrationUuidCodec.encode(UUID.randomUUID());
            row.tenantId = tenant;
            row.connectorId = connector;
            row.leaseToken = lease.token();
            row.ownerId = ownerId.strip();
            row.acquiredAt = now;
            row.heartbeatAt = now;
            row.expiresAt = leaseExpiresAt;
            mapper.insert(row);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                    "当前订货宝连接器已有统一同步编排批次运行中", List.of());
        }
        log.info("订货宝统一编排租约已获取 tenantId={} connectorId={} ownerId={}",
                tenantId, connectorId, ownerId);
        return lease;
    }

    private Lease renew(UUID tenantId, UUID connectorId, String token) {
        require(tenantId, connectorId);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token不能为空");
        }
        LocalDateTime now = now();
        LocalDateTime expiresAt = now.plus(properties.getTtl());
        int changed = mapper.update(null, Wrappers.<IntegrationDhbOrchestrationLeaseEntity>update()
                .set("heartbeat_at", now)
                .set("expires_at", expiresAt)
                .eq("tenant_id", IntegrationUuidCodec.encode(tenantId))
                .eq("connector_id", IntegrationUuidCodec.encode(connectorId))
                .eq("lease_token", token)
                .gt("expires_at", now));
        if (changed != 1) {
            throw lost();
        }
        return new Lease(token, expiresAt.toInstant(ZoneOffset.UTC));
    }

    private void release(UUID tenantId, UUID connectorId, String token) {
        if (token == null || token.isBlank()) return;
        try {
            int changed = mapper.delete(Wrappers.<IntegrationDhbOrchestrationLeaseEntity>query()
                    .eq("tenant_id", IntegrationUuidCodec.encode(tenantId))
                    .eq("connector_id", IntegrationUuidCodec.encode(connectorId))
                    .eq("lease_token", token));
            if (changed == 1) {
                log.info("订货宝统一编排租约已释放 tenantId={} connectorId={}", tenantId, connectorId);
            } else {
                log.warn("订货宝统一编排租约释放未命中 tenantId={} connectorId={}", tenantId, connectorId);
            }
        } catch (RuntimeException error) {
            log.warn("订货宝统一编排租约释放异常 tenantId={} connectorId={} errorType={}",
                    tenantId, connectorId, error.getClass().getSimpleName());
        }
    }

    private static BusinessException lost() {
        return new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                "订货宝统一同步编排租约已丢失，本轮结果不得作为成功批次", List.of());
    }

    private static void require(UUID tenantId, UUID connectorId) {
        if (tenantId == null || connectorId == null) {
            throw new IllegalArgumentException("tenantId和connectorId不能为空");
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record Lease(String token, Instant expiresAt) { }
}
