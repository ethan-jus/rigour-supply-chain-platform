package com.rigour.shared.context;

import java.util.Set;
import java.util.UUID;

/** 领域服务可消费的已签名当前调用人；显示资料应从IAM `/me`或本地投影读取。 */
public record CallerIdentity(
        String principalScope, UUID principalId, UUID tenantId, UUID userId, UUID platformUserId,
        UUID sessionId, long sessionVersion, long userSecurityVersion, long tenantPolicyVersion,
        Set<String> roles, Set<String> permissions
) {
    public CallerIdentity {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
        if (principalId == null || sessionId == null || sessionVersion < 0 || userSecurityVersion < 0
                || tenantPolicyVersion < 0) throw new IllegalArgumentException("Invalid caller identity");
        if ("TENANT".equals(principalScope)) {
            if (tenantId == null || userId == null || !principalId.equals(userId) || platformUserId != null) {
                throw new IllegalArgumentException("Invalid tenant caller identity");
            }
        } else if ("PLATFORM".equals(principalScope)) {
            if (tenantId != null || userId != null || platformUserId == null || !principalId.equals(platformUserId)
                    || tenantPolicyVersion != 0) throw new IllegalArgumentException("Invalid platform caller identity");
        } else throw new IllegalArgumentException("Invalid principal scope");
    }
}
