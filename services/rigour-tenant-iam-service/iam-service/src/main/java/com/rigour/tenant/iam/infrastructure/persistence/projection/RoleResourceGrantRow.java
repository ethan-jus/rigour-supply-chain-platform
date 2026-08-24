package com.rigour.tenant.iam.infrastructure.persistence.projection;

import java.util.UUID;

/** 角色与资源授权关系查询行。 */
public final class RoleResourceGrantRow {
    private UUID roleId;
    private UUID resourceId;

    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public void setResourceId(UUID resourceId) {
        this.resourceId = resourceId;
    }
}
