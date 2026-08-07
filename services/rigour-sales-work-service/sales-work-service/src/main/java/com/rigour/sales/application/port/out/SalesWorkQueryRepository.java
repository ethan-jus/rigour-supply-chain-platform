package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sales Work 阶段 1 查询端口；只读取本服务的身份、规则和CRM只读投影。 */
public interface SalesWorkQueryRepository {

    Optional<IdentityProjection> findIdentityProjection(UUID tenantId, UUID platformUserId);

    Optional<SalesProfile> findActiveSalesProfile(UUID tenantId, UUID employeeId, Instant at);

    Optional<FieldPolicy> findActiveFieldPolicy(UUID tenantId, UUID salesProfileId, UUID cityOrgId, Instant at);

    Optional<FieldPolicy> findFieldPolicy(UUID tenantId, UUID fieldPolicyVersionId);

    List<VisitTarget> findAssignedStoreTargets(UUID tenantId, UUID salesProfileId,
                                                String query, int limit, int offset);

    long countAssignedStoreTargets(UUID tenantId, UUID salesProfileId, String query);

    record IdentityProjection(UUID platformUserId, UUID employeeId, String status) {
    }

    record SalesProfile(UUID id, UUID employeeId, String salesNo, UUID cityOrgId, String status) {
    }

    record FieldPolicy(UUID id, String policyCode, String policyName, int versionNo,
                       String publishStatus, String timezoneId, LocalTime businessDayCutoff,
                       LocalTime checkInWindowStart, LocalTime checkInWindowEnd,
                       LocalTime checkOutWindowStart, LocalTime checkOutWindowEnd,
                       int standardWorkMinutes, int minimumWorkMinutes, boolean requireCheckOut,
                       boolean allowAdjustment, Integer adjustmentDeadlineHours, boolean locationEnabled,
                       int locationIntervalMinutes, BigDecimal minimumLocationAccuracyMeters,
                       int offlineUploadDeadlineMinutes) {
    }

    record VisitTarget(UUID projectionId, String targetType, UUID customerId, UUID storeId,
                       String customerName, String storeName, String storeAddress,
                       BigDecimal longitude, BigDecimal latitude, String storeStatus,
                       long sourceVersion, Instant sourceUpdatedAt) {
    }
}
