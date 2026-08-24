package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.RolePermissionModels.*;
import java.util.List;
import java.util.UUID;

/** 角色权限管理持久化端口；实现必须校验租户边界、套餐边界和系统角色保护。 */
public interface IamRolePermissionStore {

    List<RolePermissionView> roles(Actor actor);

    List<GrantableResourceView> grantableResources(Actor actor);

    RolePermissionView createRole(Actor actor, RolePermissionCommand command);

    RolePermissionView updateRole(Actor actor, UUID id, RolePermissionCommand command);
}
