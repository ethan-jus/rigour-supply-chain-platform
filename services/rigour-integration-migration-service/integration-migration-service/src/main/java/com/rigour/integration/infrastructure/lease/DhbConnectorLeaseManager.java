package com.rigour.integration.infrastructure.lease;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationConnectorSyncLeaseEntity;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationConnectorSyncLeaseMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** MyBatis-Plus实现的订货宝连接器分布式租约；所有权只由随机令牌判定。 */
@Component
public final class DhbConnectorLeaseManager {
    private static final Logger log = LoggerFactory.getLogger(DhbConnectorLeaseManager.class);
    private final IntegrationConnectorSyncLeaseMapper mapper;
    private final DhbConnectorLeaseProperties properties;

    public DhbConnectorLeaseManager(IntegrationConnectorSyncLeaseMapper mapper,
                                    DhbConnectorLeaseProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        properties.validate();
    }

    /** 获取租户连接器级租约；已被其他请求占用时返回稳定409业务错误。 */
    public Lease acquire(UUID tenantId, UUID connectorId, String ownerId) {
        if (tenantId == null || connectorId == null) {
            throw new IllegalArgumentException("订货宝连接器租约必须包含tenantId和connectorId");
        }
        byte[] tenant = IntegrationUuidCodec.encode(tenantId);
        byte[] connector = IntegrationUuidCodec.encode(connectorId);
        LocalDateTime now = now();
        mapper.delete(Wrappers.<IntegrationConnectorSyncLeaseEntity>query()
                .eq("tenant_id", tenant)
                .eq("connector_id", connector)
                .le("expires_at", now));
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId不能为空");
        Lease lease = new Lease(tenantId, connectorId, UUID.randomUUID().toString());
        Duration ttl = properties.getTtl();
        try {
            IntegrationConnectorSyncLeaseEntity row = new IntegrationConnectorSyncLeaseEntity();
            row.id = IntegrationUuidCodec.encode(UUID.randomUUID());
            row.tenantId = tenant;
            row.connectorId = connector;
            row.leaseToken = lease.token();
            row.ownerId = ownerId.strip();
            row.acquiredAt = now;
            row.heartbeatAt = now;
            row.expiresAt = now.plus(ttl);
            mapper.insert(row);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                    "当前订货宝连接器已有其他领域同步任务运行中", List.of());
        }
        log.info("订货宝连接器租约已获取 ownerId={} tenantId={} connectorId={}",
                ownerId, tenantId, connectorId);
        return lease;
    }

    /** 仅精确匹配随机令牌的调用可以释放租约；ownerId只用于审计。 */
    public void release(UUID tenantId, UUID connectorId, String token) {
        require(tenantId, connectorId, token);
        int changed = mapper.delete(Wrappers.<IntegrationConnectorSyncLeaseEntity>query()
                .eq("tenant_id", IntegrationUuidCodec.encode(tenantId))
                .eq("connector_id", IntegrationUuidCodec.encode(connectorId))
                .eq("lease_token", token));
        if (changed == 1) {
            log.info("订货宝连接器租约已释放 tenantId={} connectorId={}", tenantId, connectorId);
        } else {
            log.warn("订货宝连接器租约释放未命中 tenantId={} connectorId={}", tenantId, connectorId);
        }
    }

    /** 续租必须精确匹配当前令牌；过期或已被接管时返回稳定冲突码。 */
    public Lease renew(UUID tenantId, UUID connectorId, String token) {
        require(tenantId, connectorId, token);
        LocalDateTime now = now();
        int changed = mapper.update(null, Wrappers.<IntegrationConnectorSyncLeaseEntity>update()
                .set("heartbeat_at", now)
                .set("expires_at", now.plus(properties.getTtl()))
                .eq("tenant_id", IntegrationUuidCodec.encode(tenantId))
                .eq("connector_id", IntegrationUuidCodec.encode(connectorId))
                .eq("lease_token", token)
                .gt("expires_at", now));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                    "订货宝连接器同步租约已过期或被其他任务接管", List.of());
        }
        return new Lease(tenantId, connectorId, token);
    }

    public Duration ttl() { return properties.getTtl(); }

    private static void require(UUID tenantId, UUID connectorId, String token) {
        if (tenantId == null || connectorId == null || token == null || token.isBlank()) {
            throw new IllegalArgumentException("连接器租约tenantId、connectorId和token不能为空");
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 单个HTTP请求持有的不可变租约凭据。 */
    public record Lease(UUID tenantId, UUID connectorId, String token) { }
}
