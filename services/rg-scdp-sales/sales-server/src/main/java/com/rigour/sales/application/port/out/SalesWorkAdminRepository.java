package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 销售管理维护持久化端口；只写 Sales Work 自有 Schema。
 * 门店/归属投影写入是 CRM 事件消费者上线前的临时前置，消费者投产后移除。
 */
public interface SalesWorkAdminRepository {

    IdentityBindingRow upsertIdentityBinding(UUID tenantId, UUID platformUserId, UUID employeeId,
                                             Instant now);

    SalesProfileRow upsertSalesProfile(UUID tenantId, UUID employeeId, String salesNo, UUID cityOrgId,
                                       Instant now);

    /** 按 policyCode 找或建规则稳定身份，返回 policyId 与下一个版本号。 */
    PolicyIdentity ensureFieldPolicy(UUID tenantId, String policyCode, String policyName, Instant now);

    PolicyIdentity ensureVisitPolicy(UUID tenantId, String policyCode, String policyName, Instant now);

    UUID insertFieldPolicyVersion(UUID tenantId, UUID policyId, int versionNo, boolean publish,
                                  String timezoneId, LocalTime businessDayCutoff,
                                  LocalTime checkInWindowStart, LocalTime checkInWindowEnd,
                                  LocalTime checkOutWindowStart, LocalTime checkOutWindowEnd,
                                  int standardWorkMinutes, int minimumWorkMinutes,
                                  boolean requireCheckOut, boolean allowAdjustment,
                                  Integer adjustmentDeadlineHours, boolean locationEnabled,
                                  int locationIntervalMinutes, BigDecimal minimumLocationAccuracyMeters,
                                  int offlineUploadDeadlineMinutes, UUID actorId, Instant now);

    UUID insertVisitPolicyVersion(UUID tenantId, UUID policyId, int versionNo, boolean publish,
                                  boolean requireAssignedTarget, boolean allowProspectTarget,
                                  int checkInRadiusMeters, int minimumDwellMinutes,
                                  int requiredPhotoCount, boolean recordingEnabled,
                                  int minimumRecordingSeconds, int maximumClipGapSeconds,
                                  boolean aiAsrEnabled, boolean aiRelevanceEnabled,
                                  boolean aiDuplicateEnabled, BigDecimal aiAutoConfirmThreshold,
                                  UUID actorId, Instant now);

    void insertPolicyScope(UUID tenantId, String policyType, UUID policyVersionId, String scopeType,
                           UUID scopeId, UUID actorId, Instant now);

    StoreProjectionRow upsertStoreProjection(UUID tenantId, UUID storeId, UUID customerId,
                                             String customerName, String storeName, String storeAddress,
                                             BigDecimal longitude, BigDecimal latitude, String storeStatus,
                                             Instant now);

    Optional<AssignmentRow> findActiveAssignment(UUID tenantId, UUID salesProfileId, UUID storeId,
                                                 String assignmentType);

    AssignmentRow insertAssignment(UUID tenantId, UUID salesProfileId, UUID storeId, UUID customerId,
                                   String assignmentType, Instant now);

    boolean salesProfileExists(UUID tenantId, UUID salesProfileId);

    record IdentityBindingRow(UUID id, UUID platformUserId, UUID employeeId, String status) {
    }

    record SalesProfileRow(UUID id, UUID employeeId, String salesNo, UUID cityOrgId, String status) {
    }

    record PolicyIdentity(UUID policyId, int nextVersionNo) {
    }

    record StoreProjectionRow(UUID storeId, UUID customerId, String storeName, String storeStatus) {
    }

    record AssignmentRow(UUID id, UUID salesProfileId, UUID storeId, UUID customerId,
                         String assignmentType, String status) {
    }
}
