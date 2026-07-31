package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** 在IAM本服务内把JWT版本声明与当前会话、用户和租户事实比对，实现即时撤销。 */
public final class IamCurrentSessionTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error(
            "invalid_token", "IAM session is no longer current", null);
    private final JdbcTemplate jdbcTemplate;

    public IamCurrentSessionTokenValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            UUID sessionId = UUID.fromString(token.getClaimAsString("sessionId"));
            UUID principalId = UUID.fromString(token.getClaimAsString("principalId"));
            long sessionVersion = number(token, "sessionVersion");
            long securityVersion = number(token, "userSecurityVersion");
            String scope = token.getClaimAsString("principalScope");
            Integer count = "PLATFORM".equals(scope)
                    ? platformCount(sessionId, principalId, sessionVersion, securityVersion)
                    : tenantCount(token, sessionId, principalId, sessionVersion, securityVersion);
            return count != null && count == 1
                    ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(INVALID);
        } catch (IllegalArgumentException exception) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
    }

    private Integer platformCount(UUID sessionId, UUID principalId, long sessionVersion, long securityVersion) {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM iam_auth_session s
                          JOIN iam_platform_user u ON u.id = s.principal_id
                         WHERE s.id = ? AND s.principal_scope = 'PLATFORM' AND s.tenant_id IS NULL
                           AND s.principal_id = ? AND s.status = 'ACTIVE' AND s.expires_at > ?
                           AND s.version = ? AND u.security_version = ?
                           AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                        """, Integer.class, UuidBinaryCodec.encode(sessionId), UuidBinaryCodec.encode(principalId),
                LocalDateTime.now(ZoneOffset.UTC), sessionVersion, securityVersion);
    }

    private Integer tenantCount(
            Jwt token, UUID sessionId, UUID principalId, long sessionVersion, long securityVersion) {
        UUID tenantId = UUID.fromString(token.getClaimAsString("tenantId"));
        long policyVersion = number(token, "tenantPolicyVersion");
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM iam_auth_session s
                          JOIN iam_user u ON u.tenant_id = s.tenant_id AND u.id = s.principal_id
                          JOIN iam_tenant t ON t.id = s.tenant_id
                         WHERE s.id = ? AND s.principal_scope = 'TENANT' AND s.tenant_id = ?
                           AND s.principal_id = ? AND s.status = 'ACTIVE' AND s.expires_at > ?
                           AND s.version = ? AND u.security_version = ? AND t.policy_version = ?
                           AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                           AND t.status = 'ACTIVE' AND t.deleted_at IS NULL
                        """, Integer.class, UuidBinaryCodec.encode(sessionId), UuidBinaryCodec.encode(tenantId),
                UuidBinaryCodec.encode(principalId), LocalDateTime.now(ZoneOffset.UTC),
                sessionVersion, securityVersion, policyVersion);
    }

    private static long number(Jwt token, String claim) {
        Object value = token.getClaims().get(claim);
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalArgumentException(claim + " is invalid");
        }
        return number.longValue();
    }
}
