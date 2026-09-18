package com.rigour.tenant.iam.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 为已持久化的IAM租户会话签发平台Access Token。 */
public interface AccessTokenIssuer {

    String issue(TenantClaims claims);

    record TenantClaims(
            UUID sessionId,
            UUID tenantId,
            UUID userId,
            long sessionVersion,
            long userSecurityVersion,
            long tenantPolicyVersion,
            Instant issuedAt,
            Instant expiresAt
    ) {
        public TenantClaims {
            Objects.requireNonNull(sessionId, "sessionId cannot be null");
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(userId, "userId cannot be null");
            Objects.requireNonNull(issuedAt, "issuedAt cannot be null");
            Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
            if (sessionVersion < 0 || userSecurityVersion < 0 || tenantPolicyVersion < 0
                    || !expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("access token claims are invalid");
            }
        }
    }
}
