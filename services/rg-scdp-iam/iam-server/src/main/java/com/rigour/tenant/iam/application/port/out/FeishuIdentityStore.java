package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.domain.model.session.AuthSession;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 飞书外部身份映射与统一会话持久化端口。 */
public interface FeishuIdentityStore {

    Optional<BoundIdentity> findActive(String externalTenantKey, String externalUserId);

    void completeLogin(BoundIdentity identity, AuthSession session, Instant verifiedAt);

    record BoundIdentity(
            UUID externalIdentityId,
            UUID tenantId,
            UUID userId,
            String displayName,
            long externalIdentityVersion,
            long userSecurityVersion,
            long tenantPolicyVersion
    ) {
        public BoundIdentity {
            Objects.requireNonNull(externalIdentityId, "externalIdentityId cannot be null");
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(userId, "userId cannot be null");
            Objects.requireNonNull(displayName, "displayName cannot be null");
            if (externalIdentityVersion < 0 || userSecurityVersion < 0 || tenantPolicyVersion < 0) {
                throw new IllegalArgumentException("identity versions cannot be negative");
            }
        }
    }
}
