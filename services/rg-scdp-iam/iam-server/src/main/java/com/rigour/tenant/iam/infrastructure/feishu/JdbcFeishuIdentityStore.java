package com.rigour.tenant.iam.infrastructure.feishu;

import com.rigour.tenant.iam.application.port.out.FeishuIdentityStore;
import com.rigour.tenant.iam.domain.model.session.AuthSession;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** 基于既有iam_external_identity映射完成飞书登录，禁止首次登录自动绑定IAM用户。 */
public final class JdbcFeishuIdentityStore implements FeishuIdentityStore {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transaction;

    public JdbcFeishuIdentityStore(JdbcTemplate jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Optional<BoundIdentity> findActive(String externalTenantKey, String externalUserId) {
        return jdbc.query("""
                SELECT e.id AS external_identity_id, e.tenant_id, e.user_id,
                       e.version AS external_identity_version,
                       u.display_name, u.security_version, t.policy_version
                  FROM iam_external_identity e
                  JOIN iam_user u ON u.tenant_id=e.tenant_id AND u.id=e.user_id
                  JOIN iam_tenant t ON t.id=e.tenant_id
                 WHERE e.provider='FEISHU' AND e.external_tenant_key=? AND e.external_user_id=?
                   AND e.status='ACTIVE' AND e.deleted_at IS NULL
                   AND u.status='ACTIVE' AND u.deleted_at IS NULL
                   AND t.status='ACTIVE' AND t.deleted_at IS NULL
                """, (resultSet, rowNumber) -> new BoundIdentity(
                        UuidBinaryCodec.decode(resultSet.getBytes("external_identity_id")),
                        UuidBinaryCodec.decode(resultSet.getBytes("tenant_id")),
                        UuidBinaryCodec.decode(resultSet.getBytes("user_id")),
                        resultSet.getString("display_name"),
                        resultSet.getLong("external_identity_version"),
                        resultSet.getLong("security_version"),
                        resultSet.getLong("policy_version")),
                externalTenantKey, externalUserId).stream().findFirst();
    }

    @Override
    public void completeLogin(BoundIdentity identity, AuthSession session, Instant verifiedAt) {
        transaction.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE iam_external_identity
                       SET last_verified_at=?, updated_at=?, version=version+1
                     WHERE id=? AND tenant_id=? AND user_id=? AND provider='FEISHU'
                       AND status='ACTIVE' AND deleted_at IS NULL AND version=?
                    """, utc(verifiedAt), utc(verifiedAt),
                    UuidBinaryCodec.encode(identity.externalIdentityId()),
                    UuidBinaryCodec.encode(identity.tenantId()),
                    UuidBinaryCodec.encode(identity.userId()), identity.externalIdentityVersion());
            if (updated != 1) {
                throw new ConcurrencyFailureException("Feishu identity changed concurrently");
            }
            jdbc.update("""
                    INSERT INTO iam_auth_session (
                        id, principal_scope, tenant_id, principal_id, client_type, device_name,
                        client_fingerprint_hash, user_agent_hash, ip_address,
                        issued_at, last_seen_at, expires_at, status, version
                    ) VALUES (?, 'TENANT', ?, ?, 'FEISHU_H5', ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0)
                    """, UuidBinaryCodec.encode(session.id()), UuidBinaryCodec.encode(session.tenantId()),
                    UuidBinaryCodec.encode(session.principalId()), session.deviceName(),
                    session.clientFingerprintHash(), session.userAgentHash(), session.ipAddress(),
                    utc(session.issuedAt()), utc(session.lastSeenAt()), utc(session.expiresAt()));
        });
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
