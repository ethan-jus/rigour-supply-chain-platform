package com.rigour.tenant.iam.api.v1.model;

import java.util.Set;
import java.util.UUID;

/** SCDP当前登录人视图；不返回凭据、Token或可写业务状态。 */
public record CurrentUserView(
        UUID id,
        UUID tenantId,
        String tenantName,
        String principalScope,
        String username,
        String displayName,
        Set<String> roles,
        Set<String> permissions
) {
    public CurrentUserView {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
}
