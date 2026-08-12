package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sales Work 阶段 1 查询端口；只读取本服务的身份、规则和CRM只读投影。 */
public interface SalesWorkQueryRepository {

    Optional<IdentityProjection> findIdentityProjection(UUID tenantId, UUID platformUserId, Instant at);

    Optional<SalesProfile> findActiveSalesProfile(UUID tenantId, UUID employeeId, Instant at);

    Optional<FieldPolicy> findActiveFieldPolicy(UUID tenantId, UUID salesProfileId, UUID cityOrgId, Instant at);

    Optional<FieldPolicy> findFieldPolicy(UUID tenantId, UUID fieldPolicyVersionId);

    Optional<VisitPolicy> findActiveVisitPolicy(UUID tenantId, UUID salesProfileId, UUID cityOrgId, Instant at);

    Optional<VisitPolicy> findVisitPolicy(UUID tenantId, UUID visitPolicyVersionId);

    Optional<StoreProjection> findStoreById(UUID tenantId, UUID storeId);

    boolean isStoreAssignedToProfile(UUID tenantId, UUID salesProfileId, UUID storeId, Instant at);

    boolean existsStore(UUID tenantId, UUID storeId);

    List<VisitTarget> findAssignedStoreTargets(UUID tenantId, UUID salesProfileId,
                                                String query, int limit, int offset, Instant at);

    long countAssignedStoreTargets(UUID tenantId, UUID salesProfileId, String query, Instant at);

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
                       int offlineUploadDeadlineMinutes, Instant effectiveFrom) {
    }

    record VisitTarget(UUID projectionId, String targetType, UUID customerId, UUID storeId,
                       String customerName, String storeName, String storeAddress,
                       BigDecimal longitude, BigDecimal latitude, String storeStatus,
                       long sourceVersion, Instant sourceUpdatedAt) {
    }

    record VisitPolicy(UUID id, String policyCode, String policyName, int versionNo,
                       String publishStatus, boolean requireAssignedTarget, boolean allowProspectTarget,
                       int checkInRadiusMeters, int minimumDwellMinutes, int requiredPhotoCount,
                       boolean recordingEnabled, int minimumRecordingSeconds, int maximumClipGapSeconds) {
    }

    record StoreProjection(UUID storeId, UUID customerId, String customerName, String storeName,
                           String storeAddress, BigDecimal longitude, BigDecimal latitude,
                           String storeStatus) {
    }
}
