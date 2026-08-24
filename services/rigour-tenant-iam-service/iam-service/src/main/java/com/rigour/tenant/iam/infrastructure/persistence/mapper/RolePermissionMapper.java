package com.rigour.tenant.iam.infrastructure.persistence.mapper;

import com.rigour.tenant.iam.infrastructure.persistence.projection.GrantableResourceRow;
import com.rigour.tenant.iam.infrastructure.persistence.projection.RoleResourceGrantRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/** 角色权限管理复杂查询与批量授权Mapper。 */
public interface RolePermissionMapper {

    int countTenantPermission(@Param("tenantId") UUID tenantId,
                              @Param("userId") UUID userId,
                              @Param("permission") String permission);

    List<RoleResourceGrantRow> selectActiveRoleResources(@Param("tenantId") UUID tenantId);

    List<GrantableResourceRow> selectEntitledResources(@Param("tenantId") UUID tenantId);

    int inactivateRoleResources(@Param("tenantId") UUID tenantId,
                                @Param("roleId") UUID roleId,
                                @Param("actorId") UUID actorId);

    int upsertRoleResource(@Param("tenantId") UUID tenantId,
                           @Param("roleId") UUID roleId,
                           @Param("resourceId") UUID resourceId,
                           @Param("actorId") UUID actorId);

    int bumpTenantPolicy(@Param("tenantId") UUID tenantId);

    void insertAudit(@Param("id") UUID id,
                     @Param("tenantId") UUID tenantId,
                     @Param("actorScope") String actorScope,
                     @Param("actorId") UUID actorId,
                     @Param("action") String action,
                     @Param("targetType") String targetType,
                     @Param("targetId") UUID targetId,
                     @Param("requestId") UUID requestId);
}
