package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldPolicyVersionView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.AssignmentCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.AssignmentView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldPolicyCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.IdentityBindingCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.IdentityBindingView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.PolicyVersionView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.SalesProfileCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.SalesProfileView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.StoreProjectionCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.StoreProjectionView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.VisitPolicyCommand;
import com.rigour.sales.application.port.out.SalesWorkAdminRepository;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 销售管理维护用例：身份绑定、画像、规则发布与 CRM 投影的写入。
 * 只维护 Sales Work 自有 Schema；门店/归属投影写入是 CRM 事件消费者上线前的临时前置。
 */
@Service
public class SalesWorkAdminService {

    private static final Set<String> SCOPE_TYPES = Set.of("ALL", "EMPLOYEE", "CITY", "TEAM");
    private static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final SalesWorkAdminRepository repository;
    private final AuditSink auditSink;
    private final Clock clock;

    public SalesWorkAdminService(SalesWorkAdminRepository repository, AuditSink auditSink, Clock clock) {
        this.repository = repository;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    @Transactional
    public IdentityBindingView bindIdentity(IdentityBindingCommand command) {
        CallerIdentity caller = requireCaller("sales:identity:bind");
        if (command == null || command.platformUserId() == null || command.employeeId() == null) {
            throw invalid("platformUserId和employeeId不能为空");
        }
        var row = repository.upsertIdentityBinding(caller.tenantId(), command.platformUserId(),
                command.employeeId(), clock.instant());
        appendAudit(caller, "SALES_ADMIN_IDENTITY_BIND", "SALES_IDENTITY_PROJECTION", row.id(),
                Map.of("employeeId", row.employeeId().toString()));
        return new IdentityBindingView(row.id(), row.platformUserId(), row.employeeId(), row.status());
    }

    @Transactional
    public SalesProfileView upsertSalesProfile(SalesProfileCommand command) {
        CallerIdentity caller = requireCaller("sales:profile:write");
        if (command == null || command.employeeId() == null) {
            throw invalid("employeeId不能为空");
        }
        String salesNo = required(command.salesNo(), "salesNo", 64);
        var row = repository.upsertSalesProfile(caller.tenantId(), command.employeeId(), salesNo,
                command.cityOrgId(), clock.instant());
        appendAudit(caller, "SALES_ADMIN_PROFILE_UPSERT", "SALES_PROFILE", row.id(),
                Map.of("salesNo", row.salesNo(), "status", row.status()));
        return new SalesProfileView(row.id(), row.employeeId(), row.salesNo(), row.cityOrgId(), row.status());
    }

    public java.util.List<com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldTeamOption> fieldTeams() {
        return repository.fieldTeams(requireCaller("sales:policy:read").tenantId());
    }

    public List<FieldPolicyVersionView> fieldPolicies() {
        return repository.fieldPolicies(requireCaller("sales:policy:read").tenantId());
    }

    @Transactional
    public PolicyVersionView upsertFieldPolicy(FieldPolicyCommand command) {
        CallerIdentity caller = requireCaller("sales:policy:write");
        if (command != null && command.publish()) {
            AuthorizationContext.requirePermission("sales:policy:publish");
        }
        validateFieldPolicy(command);
        if ("TEAM".equals(command.scopeType()) && repository.fieldTeams(caller.tenantId()).stream().noneMatch(team -> team.id().equals(command.scopeId())))
            throw invalid("请选择当前租户的有效销售团队");
        Instant now = clock.instant();
        var policy = repository.ensureFieldPolicy(caller.tenantId(), command.policyCode().trim(),
                command.policyName().trim(), now);
        if (command.expectedVersion() != null && command.expectedVersion() != policy.nextVersionNo() - 1)
            throw new com.rigour.shared.core.exception.StateConflictException("外勤规则已更新，请刷新后重新确认");
        var versions = repository.fieldPolicies(caller.tenantId()).stream()
                .filter(row -> row.policy().policyCode().equals(command.policyCode().trim())).toList();
        var previous = versions.stream().filter(row -> "PUBLISHED".equals(row.publishStatus())
                && row.effectiveFrom() != null && !row.effectiveFrom().isAfter(now))
                .max(java.util.Comparator.comparingInt(FieldPolicyVersionView::versionNo)).orElse(null);
        if (previous != null && versions.stream().filter(row -> row.id().equals(previous.id())).count() > 1)
            throw invalid("当前规则包含多个适用范围，请先按范围整理规则，不能覆盖发布");
        Instant effectiveFrom = now;
        if (previous != null) {
            var local = now.atZone(ZoneId.of(previous.policy().timezoneId()));
            var boundary = local.toLocalDate().atTime(previous.policy().businessDayCutoff()).atZone(local.getZone());
            if (!boundary.toInstant().isAfter(now)) boundary = boundary.plusDays(1);
            effectiveFrom = boundary.toInstant();
            var firstEnd = previous.effectiveFrom().atZone(local.getZone()).toLocalDate().plusDays(1)
                    .atTime(previous.policy().businessDayCutoff()).atZone(local.getZone()).toInstant();
            if (now.isBefore(firstEnd) && effectiveFrom.isBefore(firstEnd)) effectiveFrom = firstEnd;
        }
        UUID versionId = repository.insertFieldPolicyVersion(caller.tenantId(), policy.policyId(),
                policy.nextVersionNo(), command.publish(), command.timezoneId().trim(),
                command.businessDayCutoff(), command.standardWorkMinutes(),
                command.minimumWorkMinutes(), command.allowResume(), command.workHoursEnabled(), command.allowAdjustment(),
                command.adjustmentDeadlineHours(), command.locationEnabled(),
                command.locationIntervalMinutes(), command.minimumLocationAccuracyMeters(),
                command.offlineUploadDeadlineMinutes(), caller.userId(), now, effectiveFrom);
        insertScope(caller, "FIELD", versionId, command.scopeType(), command.scopeId(), now);
        appendAudit(caller, "SALES_ADMIN_FIELD_POLICY_VERSION", "SALES_FIELD_POLICY", policy.policyId(),
                Map.of("versionNo", Integer.toString(policy.nextVersionNo()),
                        "publishStatus", command.publish() ? "PUBLISHED" : "DRAFT"));
        return new PolicyVersionView(policy.policyId(), versionId, command.policyCode().trim(),
                policy.nextVersionNo(), command.publish() ? "PUBLISHED" : "DRAFT",
                command.publish() ? effectiveFrom : null);
    }

    @Transactional
    public PolicyVersionView upsertVisitPolicy(VisitPolicyCommand command) {
        CallerIdentity caller = requireCaller("sales:policy:write");
        if (command != null && command.publish()) {
            AuthorizationContext.requirePermission("sales:policy:publish");
        }
        validateVisitPolicy(command);
        Instant now = clock.instant();
        var policy = repository.ensureVisitPolicy(caller.tenantId(), command.policyCode().trim(),
                command.policyName().trim(), now);
        if(command.expectedVersion()!=null && command.expectedVersion()!=policy.nextVersionNo()-1)
            throw new com.rigour.shared.core.exception.StateConflictException("政策已更新，请刷新后重新确认");
        UUID versionId = repository.insertVisitPolicyVersion(caller.tenantId(), policy.policyId(),
                policy.nextVersionNo(), command.publish(), command.requireAssignedTarget(),
                command.allowProspectTarget(), command.checkInRadiusMeters(),
                command.minimumDwellMinutes(), command.requiredPhotoCount(), command.recordingEnabled(),
                command.minimumRecordingSeconds(), command.maximumClipGapSeconds(),
                command.aiAsrEnabled(), command.aiRelevanceEnabled(), command.aiDuplicateEnabled(),
                command.aiAutoConfirmThreshold(), caller.userId(), now);
        repository.saveVisitCaptureRules(caller.tenantId(),versionId,command.captureRules());
        insertScope(caller, "VISIT", versionId, command.scopeType(), command.scopeId(), now);
        appendAudit(caller, "SALES_ADMIN_VISIT_POLICY_VERSION", "SALES_VISIT_POLICY", policy.policyId(),
                Map.of("versionNo", Integer.toString(policy.nextVersionNo()),
                        "publishStatus", command.publish() ? "PUBLISHED" : "DRAFT"));
        return new PolicyVersionView(policy.policyId(), versionId, command.policyCode().trim(),
                policy.nextVersionNo(), command.publish() ? "PUBLISHED" : "DRAFT",
                command.publish() ? now : null);
    }

    @Transactional
    public StoreProjectionView upsertStoreProjection(StoreProjectionCommand command) {
        CallerIdentity caller = requireCaller("sales:store-projection:write");
        if (command == null || command.storeId() == null) {
            throw invalid("storeId不能为空");
        }
        String storeName = required(command.storeName(), "storeName", 256);
        String storeStatus = command.storeStatus() == null ? "ACTIVE" : command.storeStatus().trim();
        if (!STORE_STATUSES.contains(storeStatus)) {
            throw invalid("storeStatus仅支持ACTIVE或INACTIVE");
        }
        validateCoordinate(command.longitude(), command.latitude());
        var row = repository.upsertStoreProjection(caller.tenantId(), command.storeId(),
                command.customerId(), bounded(command.customerName(), 256), storeName,
                bounded(command.storeAddress(), 512), command.longitude(), command.latitude(),
                storeStatus, clock.instant());
        appendAudit(caller, "SALES_ADMIN_STORE_PROJECTION_UPSERT", "CRM_STORE_PROJECTION", row.storeId(),
                Map.of("storeName", row.storeName(), "storeStatus", row.storeStatus()));
        return new StoreProjectionView(row.storeId(), row.customerId(), row.storeName(), row.storeStatus());
    }

    @Transactional
    public AssignmentView upsertAssignment(AssignmentCommand command) {
        CallerIdentity caller = requireCaller("sales:assignment:write");
        if (command == null || command.salesProfileId() == null || command.storeId() == null) {
            throw invalid("salesProfileId和storeId不能为空");
        }
        if (!repository.salesProfileExists(caller.tenantId(), command.salesProfileId())) {
            throw new BusinessException(ErrorCode.SALES_ADMIN_TARGET_NOT_FOUND,
                    "销售画像不存在，请先维护销售画像", List.of());
        }
        String assignmentType = StringUtils.hasText(command.assignmentType())
                ? command.assignmentType().trim() : "PRIMARY";
        if (assignmentType.length() > 24) {
            throw invalid("assignmentType长度不能超过24");
        }
        var existing = repository.findActiveAssignment(caller.tenantId(), command.salesProfileId(),
                command.storeId(), assignmentType);
        if (existing.isPresent()) {
            var row = existing.get();
            return new AssignmentView(row.id(), row.salesProfileId(), row.storeId(), row.customerId(),
                    row.assignmentType(), row.status());
        }
        var row = repository.insertAssignment(caller.tenantId(), command.salesProfileId(),
                command.storeId(), command.customerId(), assignmentType, clock.instant());
        appendAudit(caller, "SALES_ADMIN_ASSIGNMENT_UPSERT", "CRM_SALES_ASSIGNMENT_PROJECTION", row.id(),
                Map.of("salesProfileId", row.salesProfileId().toString(),
                        "assignmentType", row.assignmentType()));
        return new AssignmentView(row.id(), row.salesProfileId(), row.storeId(), row.customerId(),
                row.assignmentType(), row.status());
    }

    private void insertScope(CallerIdentity caller, String policyType, UUID policyVersionId,
                             String scopeType, UUID scopeId, Instant now) {
        String normalized = scopeType == null ? "ALL" : scopeType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SCOPE_TYPES.contains(normalized)) {
            throw invalid("scopeType仅支持ALL/EMPLOYEE/CITY/TEAM");
        }
        if ("ALL".equals(normalized) && scopeId != null) {
            throw invalid("scopeType为ALL时scopeId必须为空");
        }
        if (!"ALL".equals(normalized) && scopeId == null) {
            throw invalid("scopeType为" + normalized + "时scopeId不能为空");
        }
        repository.insertPolicyScope(caller.tenantId(), policyType, policyVersionId, normalized, scopeId,
                caller.userId(), now);
    }

    private void validateFieldPolicy(FieldPolicyCommand command) {
        if (command == null) {
            throw invalid("请求体不能为空");
        }
        required(command.policyCode(), "policyCode", 64);
        required(command.policyName(), "policyName", 128);
        String timezoneId = required(command.timezoneId(), "timezoneId", 64);
        try {
            ZoneId.of(timezoneId.trim());
        } catch (RuntimeException error) {
            throw invalid("timezoneId无效");
        }
        if (command.businessDayCutoff() == null) {
            throw invalid("businessDayCutoff不能为空");
        }
        if (command.businessDayCutoff().getSecond()!=0 || command.businessDayCutoff().getNano()!=0)
            throw invalid("作业日开始时间须精确到分钟");
        if (command.standardWorkMinutes() <= 0 || command.standardWorkMinutes() > 1440 || command.minimumWorkMinutes() < 0) {
            throw invalid("standardWorkMinutes必须大于0");
        }
        if (command.minimumWorkMinutes() > command.standardWorkMinutes()) {
            throw invalid("minimumWorkMinutes不能大于standardWorkMinutes");
        }
        if (command.locationIntervalMinutes() <= 0 || command.locationIntervalMinutes() > 1440) {
            throw invalid("locationIntervalMinutes必须大于0");
        }
        if (!"Asia/Shanghai".equals(command.timezoneId())) throw invalid("销售作业统一使用北京时间");
        if (command.minimumLocationAccuracyMeters() == null || command.minimumLocationAccuracyMeters().signum() <= 0
                || command.minimumLocationAccuracyMeters().compareTo(java.math.BigDecimal.valueOf(1000)) > 0)
            throw invalid("可接受定位误差须为1至1000米");
        if (command.offlineUploadDeadlineMinutes() < 1 || command.offlineUploadDeadlineMinutes() > 10080)
            throw invalid("补传期限须为1分钟至7天");
        if (command.allowAdjustment() && (command.adjustmentDeadlineHours() == null || command.adjustmentDeadlineHours() < 1 || command.adjustmentDeadlineHours() > 168))
            throw invalid("补正期限须为1至168小时");
    }

    private void validateVisitPolicy(VisitPolicyCommand command) {
        if (command == null) {
            throw invalid("请求体不能为空");
        }
        required(command.policyCode(), "policyCode", 64);
        required(command.policyName(), "policyName", 128);
        var rules=command.captureRules();
        if(rules!=null) {
            Integer maximum=rules.maximumLocationAccuracyMeters();
            Integer departure=rules.automaticCheckoutRadiusMeters();
            if(departure!=null && (departure<1 || departure>5000 || Boolean.TRUE.equals(rules.automaticCheckout()) && departure<command.checkInRadiusMeters())) throw invalid("自动离店距离不得小于到店范围，且不能超过5000米");
            if(maximum!=null && (maximum<1 || maximum>1000)) throw invalid("定位精度上限应为1至1000米");
            if(rules.automaticRecording() && !command.recordingEnabled()) throw invalid("自动录音需要启用录音要求");
        }
        if(command.checkInRadiusMeters()>5000 || command.minimumDwellMinutes()>480 || command.requiredPhotoCount()>10 || command.minimumRecordingSeconds()>28800 || command.maximumClipGapSeconds()>3600)
            throw invalid("拜访距离、时长或照片数量超出允许范围");
        if(command.aiAutoConfirmThreshold()!=null && (command.aiAutoConfirmThreshold().signum()<0 || command.aiAutoConfirmThreshold().doubleValue()>1)) throw invalid("AI阈值应为0至1");
        if (command.checkInRadiusMeters() <= 0) {
            throw invalid("checkInRadiusMeters必须大于0");
        }
        if (command.minimumDwellMinutes() < 0 || command.requiredPhotoCount() < 1
                || command.minimumRecordingSeconds() < 0 || command.maximumClipGapSeconds() < 0) {
            throw invalid("拜访停留/录音阈值不能为负数，门头照至少要求1张");
        }
    }

    private void validateCoordinate(BigDecimal longitude, BigDecimal latitude) {
        if (longitude != null
                && (longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0)) {
            throw invalid("longitude超出有效范围");
        }
        if (latitude != null
                && (latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0)) {
            throw invalid("latitude超出有效范围");
        }
    }

    private CallerIdentity requireCaller(String permission) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private void appendAudit(CallerIdentity caller, String action, String targetType, UUID targetId,
                             Map<String, String> attributes) {
        auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                caller.userId().toString(), action, targetType, targetId.toString(), attributes,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private static String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw invalid(field + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw invalid(field + "长度不能超过" + maxLength);
        }
        return trimmed;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.SALES_ADMIN_INVALID, message, List.of());
    }
}
