package com.rigour.tenant.iam.application.model.settings;

import com.rigour.tenant.iam.application.service.settings.AppAccessModels.RoleDataScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 鉴权只需要已分配角色的授权定义，不读取管理页人数、目录和动作预览。 */
public record AppAuthorizationRole(UUID id, String status, Set<UUID> menuNodeIds, RoleDataScope dataScope) {
    public AppAuthorizationRole {
        menuNodeIds = Set.copyOf(menuNodeIds);
        if (dataScope != null)
            dataScope = new RoleDataScope(dataScope.mode(), List.copyOf(dataScope.departmentIds()));
    }
}
