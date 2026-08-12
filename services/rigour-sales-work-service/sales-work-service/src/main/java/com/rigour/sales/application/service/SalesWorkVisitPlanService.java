package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPlanListView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPlanView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetPageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.CancelVisitPlanCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementVisitPlanPageView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementVisitPlanView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.UpsertVisitPlanCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.VisitPlanProfileOptionView;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitPlanRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitPlanRepository.VisitPlanRow;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 主管计划、本人今日计划和 CRM 负责门店候选的应用服务。 */
@Service
public class SalesWorkVisitPlanService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> PLAN_STATUSES = Set.of(
            "PLANNED", "IN_PROGRESS", "COMPLETED", "CANCELLED");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final SalesWorkVisitPlanRepository repository;
    private final SalesWorkQueryRepository queryRepository;
    private final SalesWorkContextService contextService;
    private final AuditSink auditSink;
    private final Clock clock;

    public SalesWorkVisitPlanService(
            SalesWorkVisitPlanRepository repository,
            SalesWorkQueryRepository queryRepository,
            SalesWorkContextService contextService,
            AuditSink auditSink,
            Clock clock) {
        this.repository = repository;
        this.queryRepository = queryRepository;
        this.contextService = contextService;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    public VisitPlanListView ownPlans(LocalDate date) {
        CallerIdentity caller = requireCaller("sales:visit-plan:own:read");
        if (date == null) throw invalid("计划日期不能为空");
        Instant at = clock.instant();
        var identity = contextService.resolveIdentity(caller, at);
        List<VisitPlanView> items = repository.findOwnPlans(
                        caller.tenantId(), identity.profile().id(), date).stream()
                .map(SalesWorkVisitPlanService::ownView)
                .toList();
        return new VisitPlanListView(date, items);
    }

    public ManagementVisitPlanPageView managementPlans(
            LocalDate from, LocalDate to, String status, int page, int pageSize) {
        CallerIdentity caller = requireCaller("sales:visit-plan:read");
        validateRange(from, to);
        validatePage(page, pageSize);
        String normalizedStatus = normalizeStatus(status);
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<ManagementVisitPlanView> items = repository.findManagementPlans(
                        caller.tenantId(), from, to, normalizedStatus, pageSize, offset).stream()
                .map(SalesWorkVisitPlanService::managementView)
                .toList();
        return new ManagementVisitPlanPageView(from, to, normalizedStatus, items, page, pageSize,
                repository.countManagementPlans(caller.tenantId(), from, to, normalizedStatus));
    }

    public List<VisitPlanProfileOptionView> profileOptions() {
        CallerIdentity caller = requireCaller("sales:visit-plan:read");
        return repository.findActiveProfiles(caller.tenantId()).stream()
                .map(row -> new VisitPlanProfileOptionView(
                        row.salesProfileId(), row.employeeId(), row.salesNo(), row.cityOrgId()))
                .toList();
    }

    public VisitTargetPageView targetOptions(UUID salesProfileId, String query, int page, int pageSize) {
        CallerIdentity caller = requireCaller("sales:visit-plan:read");
        validatePage(page, Math.min(pageSize, 20));
        if (salesProfileId == null || repository.findActiveProfile(
                caller.tenantId(), salesProfileId).isEmpty()) {
            throw invalid("销售画像不存在或已停用");
        }
        Instant at = clock.instant();
        String normalizedQuery = normalizeQuery(query);
        int limitedPageSize = Math.min(pageSize, 20);
        int offset = Math.multiplyExact(page - 1, limitedPageSize);
        List<VisitTargetView> items = queryRepository.findAssignedStoreTargets(
                        caller.tenantId(), salesProfileId, normalizedQuery,
                        limitedPageSize, offset, at).stream()
                .map(row -> new VisitTargetView(row.projectionId(), row.targetType(), row.customerId(),
                        row.storeId(), row.customerName(), row.storeName(), row.storeAddress(),
                        row.longitude(), row.latitude(), row.storeStatus(), row.sourceVersion(),
                        row.sourceUpdatedAt()))
                .toList();
        return new VisitTargetPageView(items, page, limitedPageSize,
                queryRepository.countAssignedStoreTargets(
                        caller.tenantId(), salesProfileId, normalizedQuery, at));
    }

    @Transactional
    public ManagementVisitPlanView create(UpsertVisitPlanCommand command) {
        CallerIdentity caller = requireCaller("sales:visit-plan:write");
        ValidatedPlan validated = validateCommand(caller, command, null);
        Instant now = clock.instant();
        UUID planId = UUID.randomUUID();
        try {
            repository.insertPlan(planId, caller.tenantId(), validated.salesProfileId(),
                    validated.plannedDate(), validated.customerId(), validated.storeId(),
                    validated.objective(), caller.userId(), now);
        } catch (DataIntegrityViolationException duplicate) {
            throw invalid("该销售当天已有同一门店的未完成计划");
        }
        appendAudit(caller, "SALES_VISIT_PLAN_CREATE", planId, Map.of(
                "salesProfileId", validated.salesProfileId().toString(),
                "storeId", validated.storeId().toString(),
                "plannedDate", validated.plannedDate().toString()));
        return managementView(repository.findManagementPlan(caller.tenantId(), planId, false)
                .orElseThrow(() -> new IllegalStateException("拜访计划写入后不可见")));
    }

    @Transactional
    public ManagementVisitPlanView update(UUID planId, UpsertVisitPlanCommand command) {
        CallerIdentity caller = requireCaller("sales:visit-plan:write");
        if (planId == null || command == null || command.version() == null || command.version() < 0) {
            throw invalid("修改计划必须携带有效版本");
        }
        VisitPlanRow current = repository.findManagementPlan(caller.tenantId(), planId, true)
                .orElseThrow(() -> invalid("拜访计划不存在"));
        if (!"PLANNED".equals(current.status())) throw invalid("只有未开始计划可以修改");
        ValidatedPlan validated = validateCommand(caller, command, planId);
        try {
            if (repository.updatePlannedPlan(caller.tenantId(), planId, command.version(),
                    validated.salesProfileId(), validated.plannedDate(), validated.customerId(),
                    validated.storeId(), validated.objective(), clock.instant()) != 1) {
                throw invalid("计划状态或版本已变化，请刷新后重试");
            }
        } catch (DataIntegrityViolationException duplicate) {
            throw invalid("该销售当天已有同一门店的未完成计划");
        }
        appendAudit(caller, "SALES_VISIT_PLAN_UPDATE", planId, Map.of(
                "salesProfileId", validated.salesProfileId().toString(),
                "storeId", validated.storeId().toString(),
                "plannedDate", validated.plannedDate().toString()));
        return managementView(repository.findManagementPlan(caller.tenantId(), planId, false)
                .orElseThrow(() -> new IllegalStateException("拜访计划修改后不可见")));
    }

    @Transactional
    public ManagementVisitPlanView cancel(UUID planId, CancelVisitPlanCommand command) {
        CallerIdentity caller = requireCaller("sales:visit-plan:write");
        if (planId == null || command == null || command.version() == null || command.version() < 0) {
            throw invalid("取消计划必须携带有效版本");
        }
        VisitPlanRow current = repository.findManagementPlan(caller.tenantId(), planId, true)
                .orElseThrow(() -> invalid("拜访计划不存在"));
        if (!"PLANNED".equals(current.status())) throw invalid("只有未开始计划可以取消");
        if (repository.cancelPlannedPlan(
                caller.tenantId(), planId, command.version(), clock.instant()) != 1) {
            throw invalid("计划状态或版本已变化，请刷新后重试");
        }
        appendAudit(caller, "SALES_VISIT_PLAN_CANCEL", planId, Map.of());
        return managementView(repository.findManagementPlan(caller.tenantId(), planId, false)
                .orElseThrow(() -> new IllegalStateException("拜访计划取消后不可见")));
    }

    private ValidatedPlan validateCommand(
            CallerIdentity caller, UpsertVisitPlanCommand command, UUID excludedPlanId) {
        if (command == null || command.salesProfileId() == null
                || command.plannedDate() == null || command.storeId() == null) {
            throw invalid("销售、日期和门店不能为空");
        }
        if (command.plannedDate().isBefore(LocalDate.now(clock.withZone(BUSINESS_ZONE)))) {
            throw invalid("不能安排过去日期的拜访计划");
        }
        if (repository.findActiveProfile(caller.tenantId(), command.salesProfileId()).isEmpty()) {
            throw invalid("销售画像不存在或已停用");
        }
        Instant at = clock.instant();
        var store = queryRepository.findStoreById(caller.tenantId(), command.storeId())
                .orElseThrow(() -> invalid("CRM门店不存在或已停用"));
        if (!queryRepository.isStoreAssignedToProfile(
                caller.tenantId(), command.salesProfileId(), command.storeId(), at)) {
            throw invalid("该门店当前不在所选销售的负责范围内");
        }
        if (store.longitude() == null || store.latitude() == null) {
            throw invalid("门店缺少坐标，不能下发可执行拜访计划");
        }
        String objective = required(command.objective(), "拜访目标", 512);
        if (repository.existsActiveDuplicate(caller.tenantId(), command.salesProfileId(),
                command.plannedDate(), command.storeId(), excludedPlanId)) {
            throw invalid("该销售当天已有同一门店的未完成计划");
        }
        return new ValidatedPlan(command.salesProfileId(), command.plannedDate(),
                store.customerId(), store.storeId(), objective);
    }

    private void appendAudit(
            CallerIdentity caller, String action, UUID planId, Map<String, String> detail) {
        auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                caller.userId().toString(), action, "SALES_VISIT_PLAN", planId.toString(), detail,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private static VisitPlanView ownView(VisitPlanRow row) {
        return new VisitPlanView(row.id(), row.plannedDate(), row.targetType(), row.customerId(),
                row.storeId(), row.customerName(), row.storeName(), row.storeAddress(),
                row.longitude(), row.latitude(), row.objective(), row.status(), row.visitId(), row.version());
    }

    private static ManagementVisitPlanView managementView(VisitPlanRow row) {
        return new ManagementVisitPlanView(row.id(), row.salesProfileId(), row.salesNo(),
                row.plannedDate(), row.targetType(), row.customerId(), row.storeId(),
                row.customerName(), row.storeName(), row.storeAddress(), row.objective(),
                row.status(), row.visitId(), row.version(), row.createdAt(), row.updatedAt());
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) > 366) {
            throw invalid("计划日期范围无效或超过366天");
        }
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) throw invalid("分页参数无效");
    }

    private static String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!PLAN_STATUSES.contains(normalized)) throw invalid("计划状态无效");
        return normalized;
    }

    private static String normalizeQuery(String query) {
        if (!StringUtils.hasText(query)) return "";
        String normalized = query.trim();
        if (normalized.length() > 128) throw invalid("搜索词长度不能超过128");
        return normalized;
    }

    private static String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) throw invalid(field + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw invalid(field + "长度不能超过" + maxLength);
        return normalized;
    }

    private static CallerIdentity requireCaller(String permission) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.SALES_ADMIN_INVALID, message, List.of());
    }

    private record ValidatedPlan(
            UUID salesProfileId, LocalDate plannedDate, UUID customerId, UUID storeId,
            String objective) {
    }
}
