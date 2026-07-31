package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import com.rigour.tenant.iam.infrastructure.security.session.IamAuthenticationDetails;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

/** 签发前从数据库重新确认会话、主体和租户版本，避免使用登录时的陈旧快照。 */
public final class IamTokenClaimsResolver {

    private final JdbcTemplate jdbcTemplate;

    public IamTokenClaimsResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Claims resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)) {
            throw new IllegalStateException("Token issuance requires an authenticated IAM session");
        }
        UUID sessionId = parse(details.get(IamAuthenticationDetails.SESSION_ID), "session id");
        UUID principalId = parse(details.get(IamAuthenticationDetails.PRINCIPAL_ID), "principal id");
        String scope = String.valueOf(details.get(IamAuthenticationDetails.PRINCIPAL_SCOPE));
        return switch (scope) {
            case "PLATFORM" -> resolvePlatform(sessionId, principalId);
            case "TENANT" -> resolveTenant(sessionId, principalId);
            default -> throw new IllegalStateException("IAM principal scope is invalid");
        };
    }

    private Claims resolvePlatform(UUID sessionId, UUID principalId) {
        List<Claims> claims = jdbcTemplate.query("""
                        SELECT s.version AS session_version, u.security_version
                          FROM iam_auth_session s
                          JOIN iam_platform_user u ON u.id = s.principal_id
                         WHERE s.id = ? AND s.principal_scope = 'PLATFORM' AND s.tenant_id IS NULL
                           AND s.principal_id = ? AND s.status = 'ACTIVE' AND s.expires_at > ?
                           AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                        """, (resultSet, rowNumber) -> new Claims(
                        sessionId, "PLATFORM", null, principalId,
                        resultSet.getLong("session_version"),
                        resultSet.getLong("security_version"), 0),
                UuidBinaryCodec.encode(sessionId), UuidBinaryCodec.encode(principalId), utcNow());
        return exactlyOne(claims);
    }

    private Claims resolveTenant(UUID sessionId, UUID principalId) {
        List<Claims> claims = jdbcTemplate.query("""
                        SELECT s.tenant_id, s.version AS session_version,
                               u.security_version, t.policy_version
                          FROM iam_auth_session s
                          JOIN iam_user u ON u.tenant_id = s.tenant_id AND u.id = s.principal_id
                          JOIN iam_tenant t ON t.id = s.tenant_id
                         WHERE s.id = ? AND s.principal_scope = 'TENANT'
                           AND s.principal_id = ? AND s.status = 'ACTIVE' AND s.expires_at > ?
                           AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                           AND t.status = 'ACTIVE' AND t.deleted_at IS NULL
                        """, (resultSet, rowNumber) -> new Claims(
                        sessionId, "TENANT", UuidBinaryCodec.decode(resultSet.getBytes("tenant_id")), principalId,
                        resultSet.getLong("session_version"),
                        resultSet.getLong("security_version"),
                        resultSet.getLong("policy_version")),
                UuidBinaryCodec.encode(sessionId), UuidBinaryCodec.encode(principalId), utcNow());
        return exactlyOne(claims);
    }

    private static Claims exactlyOne(List<Claims> claims) {
        if (claims.size() != 1) {
            throw new IllegalStateException("IAM session or principal is no longer active");
        }
        return claims.getFirst();
    }

    private static UUID parse(Object value, String field) {
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("IAM " + field + " is invalid", exception);
        }
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public record Claims(
            UUID sessionId,
            String principalScope,
            UUID tenantId,
            UUID principalId,
            long sessionVersion,
            long userSecurityVersion,
            long tenantPolicyVersion
    ) {
    }
}
