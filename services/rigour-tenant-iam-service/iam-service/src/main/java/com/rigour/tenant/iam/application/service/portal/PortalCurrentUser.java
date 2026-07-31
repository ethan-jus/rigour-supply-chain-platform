package com.rigour.tenant.iam.application.service.portal;

import java.util.Set;
import java.util.UUID;

/** 应用层当前用户只读视图。 */
public record PortalCurrentUser(
        UUID id, UUID tenantId, String tenantName, String principalScope, String username, String displayName,
        Set<String> roles, Set<String> permissions
) {
    public PortalCurrentUser {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
}
