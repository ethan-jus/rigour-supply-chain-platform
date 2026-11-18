package com.rigour.sales.application.port.out;

import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldPolicyVersionView;
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

    java.util.List<FieldPolicyVersionView> fieldPolicies(UUID tenantId);

    IdentityBindingRow upsertIdentityBinding(UUID tenantId, UUID platformUserId, UUID employeeId,
                                             Instant now);

    SalesProfileRow upsertSalesProfile(UUID tenantId, UUID employeeId, String salesNo, UUID cityOrgId,
                                       Instant now);

    /** 按 policyCode 找或建规则稳定身份，返回 policyId 与下一个版本号。 */
    java.util.List<com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldTeamOption> fieldTeams(UUID tenantId);

    PolicyIdentity ensureFieldPolicy(UUID tenantId, String policyCode, String policyName, Instant now);

    PolicyIdentity ensureVisitPolicy(UUID tenantId, String policyCode, String policyName, Instant now);

    UUID insertFieldPolicyVersion(UUID tenantId, UUID policyId, int versionNo, boolean publish,
                                  String timezoneId, LocalTime businessDayCutoff,
                                  int standardWorkMinutes, int minimumWorkMinutes,
                                  boolean allowResume, boolean workHoursEnabled, boolean allowAdjustment,
                                  Integer adjustmentDeadlineHours, boolean locationEnabled,
                                  int locationIntervalMinutes, BigDecimal minimumLocationAccuracyMeters,
                                  int offlineUploadDeadlineMinutes, UUID actorId, Instant now, Instant effectiveFrom);

    UUID insertVisitPolicyVersion(UUID tenantId, UUID policyId, int versionNo, boolean publish,
                                  boolean requireAssignedTarget, boolean allowProspectTarget,
                                  int checkInRadiusMeters, int minimumDwellMinutes,
                                  int requiredPhotoCount, boolean recordingEnabled,
                                  int minimumRecordingSeconds, int maximumClipGapSeconds,
                                  boolean aiAsrEnabled, boolean aiRelevanceEnabled,
                                  boolean aiDuplicateEnabled, BigDecimal aiAutoConfirmThreshold,
                                  UUID actorId, Instant now);

    void saveVisitCaptureRules(UUID tenantId,UUID versionId,com.rigour.sales.api.v1.model.SalesVisitPolicyModels.CaptureRules rules);

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
