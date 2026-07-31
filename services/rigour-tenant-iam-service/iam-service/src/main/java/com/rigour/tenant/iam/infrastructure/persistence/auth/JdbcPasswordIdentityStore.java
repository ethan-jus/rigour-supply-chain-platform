package com.rigour.tenant.iam.infrastructure.persistence.auth;

import com.rigour.tenant.iam.application.port.out.PasswordIdentityStore;
import com.rigour.tenant.iam.domain.model.credential.Credential;
import com.rigour.tenant.iam.domain.model.credential.PasswordIdentity;
import com.rigour.tenant.iam.domain.model.credential.PasswordIdentity.PrincipalStatus;
import com.rigour.tenant.iam.domain.model.credential.PasswordIdentity.TenantStatus;
import com.rigour.tenant.iam.domain.model.session.AuthSession;
import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用行锁和乐观版本更新密码凭据，保证失败计数与会话创建不会并发丢失。 */
public final class JdbcPasswordIdentityStore implements PasswordIdentityStore {

    private static final String PLATFORM_LOOKUP = """
            SELECT u.id AS principal_id, u.username, u.display_name, u.platform_role,
                   u.status AS principal_status, u.security_version,
                   c.id AS credential_id, c.password_hash, c.algorithm, c.algorithm_version,
                   c.failed_attempts, c.locked_until, c.status AS credential_status, c.version AS credential_version
              FROM iam_platform_user u
              JOIN iam_platform_user_credential c
                ON c.platform_user_id = u.id AND c.credential_type = 'PASSWORD'
             WHERE u.username = ? AND u.deleted_at IS NULL
             FOR UPDATE
            """;

    private static final String TENANT_LOOKUP = """
            SELECT u.id AS principal_id, u.tenant_id, u.username, u.display_name,
                   u.status AS principal_status, u.security_version, t.status AS tenant_status,
                   c.id AS credential_id, c.password_hash, c.algorithm, c.algorithm_version,
                   c.failed_attempts, c.locked_until, c.status AS credential_status, c.version AS credential_version
              FROM iam_tenant t
              JOIN iam_user u ON u.tenant_id = t.id
              JOIN iam_user_credential c
                ON c.tenant_id = u.tenant_id AND c.user_id = u.id AND c.credential_type = 'PASSWORD'
             WHERE t.tenant_code = ? AND t.deleted_at IS NULL
               AND u.username = ? AND u.deleted_at IS NULL
             FOR UPDATE
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPasswordIdentityStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PasswordIdentity> findForAuthentication(
            PrincipalScope principalScope, String tenantCode, String username) {
        if (principalScope == PrincipalScope.PLATFORM) {
            return jdbcTemplate.query(PLATFORM_LOOKUP, this::mapPlatform, username).stream().findFirst();
        }
        return jdbcTemplate.query(TENANT_LOOKUP, this::mapTenant, tenantCode, username).stream().findFirst();
    }

    @Override
    public void recordFailure(
            PrincipalScope principalScope,
            UUID credentialId,
            Credential.FailureState failureState,
            long expectedVersion
    ) {
        String table = credentialTable(principalScope);
        int updated = jdbcTemplate.update("""
                        UPDATE %s
                           SET failed_attempts = ?, last_failed_at = ?, locked_until = ?,
                               updated_at = ?, version = version + 1
                         WHERE id = ? AND version = ?
                        """.formatted(table),
                failureState.failedAttempts(), utc(failureState.lastFailedAt()), utc(failureState.lockedUntil()),
                utc(failureState.lastFailedAt()), UuidBinaryCodec.encode(credentialId), expectedVersion);
        requireSingleUpdate(updated, credentialId);
    }

    @Override
    public void recordSuccess(
            PrincipalScope principalScope,
            UUID credentialId,
            String upgradedPasswordHash,
            Instant authenticatedAt,
            long expectedVersion
    ) {
        String table = credentialTable(principalScope);
        int updated;
        if (upgradedPasswordHash == null) {
            updated = jdbcTemplate.update("""
                            UPDATE %s
                               SET failed_attempts = 0, last_failed_at = NULL, locked_until = NULL,
                                   updated_at = ?, version = version + 1
                             WHERE id = ? AND version = ?
                            """.formatted(table),
                    utc(authenticatedAt), UuidBinaryCodec.encode(credentialId), expectedVersion);
        } else {
            updated = jdbcTemplate.update("""
                            UPDATE %s
                               SET password_hash = ?, algorithm = 'ARGON2ID', algorithm_version = 1,
                                   failed_attempts = 0, last_failed_at = NULL, locked_until = NULL,
                                   updated_at = ?, version = version + 1
                             WHERE id = ? AND version = ?
                            """.formatted(table),
                    upgradedPasswordHash, utc(authenticatedAt), UuidBinaryCodec.encode(credentialId), expectedVersion);
        }
        requireSingleUpdate(updated, credentialId);
    }

    @Override
    public void createSession(AuthSession session) {
        jdbcTemplate.update("""
                        INSERT INTO iam_auth_session (
                            id, principal_scope, tenant_id, principal_id, client_type, device_name,
                            client_fingerprint_hash, user_agent_hash, ip_address,
                            issued_at, last_seen_at, expires_at, status, version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0)
                        """,
                UuidBinaryCodec.encode(session.id()), session.principalScope().name(),
                UuidBinaryCodec.encode(session.tenantId()), UuidBinaryCodec.encode(session.principalId()),
                session.clientType().name(), session.deviceName(), session.clientFingerprintHash(),
                session.userAgentHash(), session.ipAddress(), utc(session.issuedAt()),
                utc(session.lastSeenAt()), utc(session.expiresAt()));
    }

    private PasswordIdentity mapPlatform(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PasswordIdentity(
                PrincipalScope.PLATFORM,
                null,
                uuid(resultSet, "principal_id"),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("platform_role"),
                PrincipalStatus.valueOf(resultSet.getString("principal_status")),
                TenantStatus.NOT_APPLICABLE,
                resultSet.getLong("security_version"),
                mapCredential(resultSet)
        );
    }

    private PasswordIdentity mapTenant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PasswordIdentity(
                PrincipalScope.TENANT,
                uuid(resultSet, "tenant_id"),
                uuid(resultSet, "principal_id"),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                null,
                PrincipalStatus.valueOf(resultSet.getString("principal_status")),
                TenantStatus.valueOf(resultSet.getString("tenant_status")),
                resultSet.getLong("security_version"),
                mapCredential(resultSet)
        );
    }

    private Credential mapCredential(ResultSet resultSet) throws SQLException {
        LocalDateTime lockedUntil = resultSet.getObject("locked_until", LocalDateTime.class);
        return new Credential(
                uuid(resultSet, "credential_id"),
                resultSet.getString("password_hash"),
                resultSet.getString("algorithm"),
                resultSet.getInt("algorithm_version"),
                resultSet.getInt("failed_attempts"),
                lockedUntil == null ? null : lockedUntil.toInstant(ZoneOffset.UTC),
                Credential.Status.valueOf(resultSet.getString("credential_status")),
                resultSet.getLong("credential_version")
        );
    }

    private static UUID uuid(ResultSet resultSet, String column) throws SQLException {
        return UuidBinaryCodec.decode(resultSet.getBytes(column));
    }

    private static String credentialTable(PrincipalScope scope) {
        return scope == PrincipalScope.PLATFORM
                ? "iam_platform_user_credential"
                : "iam_user_credential";
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireSingleUpdate(int updated, UUID credentialId) {
        if (updated != 1) {
            throw new ConcurrencyFailureException("Credential changed concurrently: " + credentialId);
        }
    }
}
