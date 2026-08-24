package com.rigour.tenant.iam.infrastructure.persistence.mapper;

import com.rigour.tenant.iam.infrastructure.persistence.projection.StaffListRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface StaffManagementMapper {
    int countTenantPermission(@Param("tenantId") UUID tenantId,
                              @Param("userId") UUID userId,
                              @Param("permission") String permission);

    int countOrganization(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    int countPosition(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    int countUser(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    List<StaffListRow> selectStaff(@Param("tenantId") UUID tenantId,
                                   @Param("keyword") String keyword,
                                   @Param("status") String status);

    StaffListRow selectStaffById(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    List<StaffListRow> selectStaffByCodes(@Param("tenantId") UUID tenantId,
                                          @Param("staffCodes") List<String> staffCodes);

    List<StaffListRow> selectDhbStaffBySource(@Param("tenantId") UUID tenantId,
                                              @Param("sourceTenantKey") String sourceTenantKey,
                                              @Param("sourceStaffIds") List<String> sourceStaffIds,
                                              @Param("sourceStaffNames") List<String> sourceStaffNames);

    void insertAudit(@Param("id") UUID id,
                     @Param("tenantId") UUID tenantId,
                     @Param("actorScope") String actorScope,
                     @Param("actorId") UUID actorId,
                     @Param("action") String action,
                     @Param("targetType") String targetType,
                     @Param("targetId") UUID targetId,
                     @Param("requestId") UUID requestId);
}
