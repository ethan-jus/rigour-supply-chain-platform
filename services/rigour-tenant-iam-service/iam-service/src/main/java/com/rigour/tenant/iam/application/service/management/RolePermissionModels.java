package com.rigour.tenant.iam.application.service.management;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 角色权限管理稳定命令与视图；角色编码、资源授权均以我方IAM模型为准。 */
public final class RolePermissionModels {

    private RolePermissionModels() {
    }

    /** 租户角色视图，resourceIds 表示当前启用的资源授权集合。 */
    public record RolePermissionView(UUID id, String code, String name, String description,
                                     String type, String status, long version,
                                     Instant updatedAt, List<UUID> resourceIds) {
        public RolePermissionView {
            resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        }
    }

    /** 自定义角色保存命令；角色编码由 IAM 业务编码规则生成，前端不得提交。 */
    public record RolePermissionCommand(String name, String description, String status,
                                        List<UUID> resourceIds, long version) {
        public RolePermissionCommand {
            resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        }
    }

    /** 可授权资源视图；只返回当前租户套餐内且导航链路有效的资源。 */
    public record GrantableResourceView(UUID id, UUID applicationId, String applicationCode,
                                        String applicationName, UUID parentId, String code,
                                        String type, String permissionCode, String displayName,
                                        int sortOrder, String status, String routeKey,
                                        String routePath, String iconKey, boolean visible,
                                        boolean keepAlive) {
    }
}
