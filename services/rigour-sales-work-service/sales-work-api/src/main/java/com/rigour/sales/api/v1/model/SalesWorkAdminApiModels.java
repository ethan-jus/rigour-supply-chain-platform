package com.rigour.sales.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 销售管理维护契约（Portal 销售管理后台/联调工具使用）。
 * 门店与归属投影写入是 CRM 事件消费者上线前的临时前置；消费者投产后这两个端点下线。
 */
public final class SalesWorkAdminApiModels {

    private SalesWorkAdminApiModels() {
    }

    /** 绑定 IAM 平台用户到 HR 员工，写入销售身份投影。 */
    public record IdentityBindingCommand(UUID platformUserId, UUID employeeId) {
    }

    public record IdentityBindingView(UUID id, UUID platformUserId, UUID employeeId, String status) {
    }

    /** 维护销售业务画像；按 (tenant, employeeId) 幂等覆盖。 */
    public record SalesProfileCommand(UUID employeeId, String salesNo, UUID cityOrgId) {
    }

    public record SalesProfileView(UUID id, UUID employeeId, String salesNo, UUID cityOrgId, String status) {
    }

    /**
     * 维护外勤规则并追加一个新版本；publish=true 时直接发布生效。
     * scopeType 支持 ALL/EMPLOYEE/CITY/TEAM，ALL 时 scopeId 必须为空。
     */
    public record FieldPolicyCommand(
            String policyCode, String policyName, String timezoneId,
            LocalTime businessDayCutoff, LocalTime checkInWindowStart, LocalTime checkInWindowEnd,
            LocalTime checkOutWindowStart, LocalTime checkOutWindowEnd,
            int standardWorkMinutes, int minimumWorkMinutes, boolean requireCheckOut,
            boolean allowAdjustment, Integer adjustmentDeadlineHours, boolean locationEnabled,
            int locationIntervalMinutes, BigDecimal minimumLocationAccuracyMeters,
            int offlineUploadDeadlineMinutes, String scopeType, UUID scopeId, boolean publish) {
    }

    /** 维护拜访规则并追加一个新版本；scopeType 语义同外勤规则。 */
    public record VisitPolicyCommand(
            String policyCode, String policyName, boolean requireAssignedTarget,
            boolean allowProspectTarget, int checkInRadiusMeters, int minimumDwellMinutes,
            int requiredPhotoCount, boolean recordingEnabled, int minimumRecordingSeconds,
            int maximumClipGapSeconds, boolean aiAsrEnabled, boolean aiRelevanceEnabled,
            boolean aiDuplicateEnabled, BigDecimal aiAutoConfirmThreshold,
            String scopeType, UUID scopeId, boolean publish) {
    }

    public record PolicyVersionView(
            UUID policyId, UUID policyVersionId, String policyCode, int versionNo, String publishStatus,
            Instant effectiveFrom) {
    }

    /** 临时前置：CRM 门店投影写入；storeId 由 CRM 主数据决定，按 (tenant, storeId) 幂等覆盖。 */
    public record StoreProjectionCommand(
            UUID storeId, UUID customerId, String customerName, String storeName, String storeAddress,
            BigDecimal longitude, BigDecimal latitude, String storeStatus) {
    }

    public record StoreProjectionView(UUID storeId, UUID customerId, String storeName, String storeStatus) {
    }

    /** 临时前置：CRM 归属投影写入；同一画像对同一门店的 ACTIVE 归属只保留一条。 */
    public record AssignmentCommand(
            UUID salesProfileId, UUID storeId, UUID customerId, String assignmentType) {
    }

    public record AssignmentView(
            UUID id, UUID salesProfileId, UUID storeId, UUID customerId, String assignmentType, String status) {
    }
}
