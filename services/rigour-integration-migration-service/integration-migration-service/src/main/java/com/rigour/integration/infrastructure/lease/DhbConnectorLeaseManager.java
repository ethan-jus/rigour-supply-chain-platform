package com.rigour.integration.infrastructure.lease;

import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 数据库实现的订货宝连接器分布式租约；所有权只由随机令牌判定，不依赖Integration实例。 */
@Component
public final class DhbConnectorLeaseManager {
    private static final Logger log = LoggerFactory.getLogger(DhbConnectorLeaseManager.class);
    private final JdbcTemplate jdbc;
    private final DhbConnectorLeaseProperties properties;

    public DhbConnectorLeaseManager(JdbcTemplate jdbc, DhbConnectorLeaseProperties properties) {
        this.jdbc = jdbc;
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
        jdbc.update("""
                DELETE FROM integration_connector_sync_lease
                 WHERE tenant_id=? AND connector_id=? AND expires_at<=UTC_TIMESTAMP(6)
                """, tenant, connector);
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId不能为空");
        Lease lease = new Lease(tenantId, connectorId, UUID.randomUUID().toString());
        Duration ttl = properties.getTtl();
        try {
            jdbc.update("""
                    INSERT INTO integration_connector_sync_lease
                        (id, tenant_id, connector_id, lease_token, owner_id,
                         acquired_at, heartbeat_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                            TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)))
                    """, IntegrationUuidCodec.encode(UUID.randomUUID()), tenant, connector,
                    lease.token(), ownerId.strip(), ttl.toNanos() / 1_000L);
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
        int changed = jdbc.update("""
                DELETE FROM integration_connector_sync_lease
                 WHERE tenant_id=? AND connector_id=? AND lease_token=?
                """, IntegrationUuidCodec.encode(tenantId), IntegrationUuidCodec.encode(connectorId), token);
        if (changed == 1) {
            log.info("订货宝连接器租约已释放 tenantId={} connectorId={}", tenantId, connectorId);
        } else {
            log.warn("订货宝连接器租约释放未命中 tenantId={} connectorId={}", tenantId, connectorId);
        }
    }

    /** 续租必须精确匹配当前令牌；过期或已被接管时返回稳定冲突码。 */
    public Lease renew(UUID tenantId, UUID connectorId, String token) {
        require(tenantId, connectorId, token);
        long ttlMicros = properties.getTtl().toNanos() / 1_000L;
        int changed = jdbc.update("""
                UPDATE integration_connector_sync_lease
                   SET heartbeat_at=UTC_TIMESTAMP(6),
                       expires_at=TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))
                 WHERE tenant_id=? AND connector_id=? AND lease_token=?
                   AND expires_at>UTC_TIMESTAMP(6)
                """, ttlMicros, IntegrationUuidCodec.encode(tenantId),
                IntegrationUuidCodec.encode(connectorId), token);
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

    /** 单个HTTP请求持有的不可变租约凭据。 */
    public record Lease(UUID tenantId, UUID connectorId, String token) { }
}
