package com.rigour.tenant.iam.domain.model.credential;

import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import java.util.Objects;
import java.util.UUID;

/** 一次密码认证所需的最小主体快照，不携带组织树和完整权限集合。 */
public record PasswordIdentity(
        PrincipalScope principalScope,
        UUID tenantId,
        UUID principalId,
        String username,
        String displayName,
        String platformRole,
        PrincipalStatus principalStatus,
        TenantStatus tenantStatus,
        long securityVersion,
        Credential credential
) {
    public PasswordIdentity {
        Objects.requireNonNull(principalScope, "principalScope cannot be null");
        Objects.requireNonNull(principalId, "principalId cannot be null");
        Objects.requireNonNull(username, "username cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        Objects.requireNonNull(principalStatus, "principalStatus cannot be null");
        Objects.requireNonNull(tenantStatus, "tenantStatus cannot be null");
        Objects.requireNonNull(credential, "credential cannot be null");
        if ((principalScope == PrincipalScope.TENANT) != (tenantId != null)) {
            throw new IllegalArgumentException("tenant identity must have tenantId and platform identity must not");
        }
        if (securityVersion < 0) {
            throw new IllegalArgumentException("securityVersion cannot be negative");
        }
    }

    public boolean isActive() {
        boolean tenantAvailable = principalScope == PrincipalScope.PLATFORM
                ? tenantStatus == TenantStatus.NOT_APPLICABLE
                : tenantStatus == TenantStatus.ACTIVE;
        return principalStatus == PrincipalStatus.ACTIVE && tenantAvailable;
    }

    public enum PrincipalStatus {
        ACTIVE,
        LOCKED,
        DISABLED
    }

    public enum TenantStatus {
        ACTIVE,
        SUSPENDED,
        EXPIRED,
        CLOSED,
        NOT_APPLICABLE
    }
}
