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
        } else if ("SERVICE".equals(principalScope)) {
            // 服务身份不代表某个用户；tenantId 可为空（跨租户发现）或绑定到当前目标租户。
            if (userId != null || platformUserId != null || tenantPolicyVersion != 0) {
                throw new IllegalArgumentException("Invalid service caller identity");
            }
        } else throw new IllegalArgumentException("Invalid principal scope");
    }
}
