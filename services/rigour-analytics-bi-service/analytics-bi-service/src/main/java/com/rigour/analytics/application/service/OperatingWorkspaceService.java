package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore.ActionFilter;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 目标修订与运营跟进用例；只记录人工判断，不替代财务确认或销售拜访执行。 */
@Service
public class OperatingWorkspaceService {
    public static final String READ = "analytics:dashboard:read";
    public static final String TARGET_WRITE = "analytics:targets:write";
    public static final String ACTION_WRITE = "analytics:operations:write";
    private static final Set<String> DIMENSIONS = Set.of("CITY", "SALES_OWNER");
    private static final Set<String> METRICS = Set.of("SALES_AMOUNT", "PAID_AMOUNT", "CONTACTED_CUSTOMER", "COOPERATED_CUSTOMER");
    private static final Set<String> KINDS = Set.of("COLLECTION", "CUSTOMER", "STOCK");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "DISMISSED");
    private static final Map<String, Set<String>> NEXT = Map.of(
            "OPEN", Set.of("OPEN", "IN_PROGRESS", "DISMISSED"),
            "IN_PROGRESS", Set.of("IN_PROGRESS", "RESOLVED", "DISMISSED"),
            "RESOLVED", Set.of("OPEN"), "DISMISSED", Set.of("OPEN"));
    private static final BigDecimal MAX_TARGET = new BigDecimal("999999999999.99");
    private final OperatingWorkspaceStore store;
    private final Clock clock;
    private final BiDataScopeService scope;

    public OperatingWorkspaceService(OperatingWorkspaceStore store, Clock analyticsClock, BiDataScopeService scope) {
        this.store = store;
        this.clock = analyticsClock;
        this.scope = scope;
    }

    public List<TargetView> targets(String month, String type, String code) {
        var actor = actor(READ);
        String dimension = optionalChoice(type, DIMENSIONS, "目标维度");
        String object = text(code, 64, false, "目标对象");
        if (object != null && "SALES_OWNER".equals(dimension)) {
            requireTargetScope(actor.tenantId().toString(), dimension, object);
            return store.targets(actor.tenantId().toString(), month(month).atDay(1), dimension, object, null, object);
        }
        var selected = scope.resolve("CITY".equals(dimension) ? object : null, null);
        return store.targets(actor.tenantId().toString(), month(month).atDay(1),
                dimension, object,
                selected.regionCode(), selected.ownerStaffCode());
    }

    public TargetView saveTarget(TargetCommand input) {
        var actor = actor(TARGET_WRITE);
        if (input == null) throw invalid("目标不能为空");
        String type = choice(input.dimensionType(), DIMENSIONS, "目标维度");
        String metric = choice(input.metricCode(), METRICS, "目标指标");
        BigDecimal value = input.targetValue();
        if (value == null || value.signum() < 0 || value.compareTo(MAX_TARGET) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw invalid("目标必须为0至999999999999.99之间、最多两位小数");
        }
        if (metric.endsWith("_CUSTOMER") && value.stripTrailingZeros().scale() > 0) {
            throw invalid("客户数目标必须为整数");
        }
        if (input.expectedRevision() == null || input.expectedRevision() < 0) throw invalid("缺少有效目标版本");
        var command = new TargetCommand(month(input.month()).toString(), type,
                text(input.dimensionCode(), 64, true, "目标对象"),
                text(input.dimensionName(), 160, true, "目标名称"), metric, value,
                text(input.remark(), 1000, false, "备注"), input.expectedRevision());
        requireTargetScope(actor.tenantId().toString(), type, command.dimensionCode());
        return store.saveTarget(actor.tenantId().toString(), actor.userId().toString(), command, Instant.now(clock));
    }

    public void deleteTarget(String id, int revision) {
        var actor = actor(TARGET_WRITE);
        if (revision < 1) throw invalid("缺少有效目标版本");
        var target = store.target(actor.tenantId().toString(), text(id, 64, true, "目标"))
                .orElseThrow(OperatingWorkspaceService::notFound);
        requireTargetScope(actor.tenantId().toString(), target.dimensionType(), target.dimensionCode());
        if (!store.deleteTarget(actor.tenantId().toString(), actor.userId().toString(),
                text(id, 64, true, "目标"), revision, Instant.now(clock))) throw conflict();
    }

    public ActionPage actions(String kind, String ref, String city, String employee, String assignee,
            String status, int page, int size) {
        var actor = actor(READ);
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw invalid("分页范围无效");
        var selected = scope.resolve(city, employee);
        return store.actions(actor.tenantId().toString(), new ActionFilter(
                optionalChoice(kind, KINDS, "跟进类型"), text(ref, 160, false, "业务对象"),
                text(selected.regionCode(), 64, false, "城市"), text(selected.ownerStaffCode(), 64, false, "员工"),
                text(assignee, 160, false, "负责人"), optionalChoice(status, STATUSES, "状态"), page, size, selected.fullTenant()));
    }

    public ActionView createAction(ActionCommand input) {
        var actor = actor(ACTION_WRITE);
        if (input == null) throw invalid("跟进不能为空");
        String kind = choice(input.kind(), KINDS, "跟进类型");
        var subject = requireSubject(actor.tenantId().toString(), kind, text(input.businessRef(), 160, true, "业务对象"));
        var command = new ActionCommand(kind, subject.businessRef(), subject.businessLabel(),
                subject.cityCode(), subject.employeeCode(),
                text(input.assignee(), 160, true, "负责人"), due(input.dueAt()),
                text(input.note(), 2000, true, "跟进内容"));
        scope.requireObjectScope(command.cityCode(), command.employeeCode());
        if ("STOCK".equals(command.kind())) scope.requireGlobalGovernance();
        requireTargetScope(actor.tenantId().toString(), "SALES_OWNER", command.assignee());
        return store.createAction(actor.tenantId().toString(), actor.userId().toString(), command, Instant.now(clock));
    }

    public ActionView updateAction(String id, ActionUpdateCommand input) {
        var actor = actor(ACTION_WRITE);
        if (input == null || input.expectedRevision() == null || input.expectedRevision() < 1) {
            throw invalid("缺少有效跟进版本");
        }
        String tenant = actor.tenantId().toString();
        var previous = store.action(tenant, text(id, 64, true, "跟进")).orElseThrow(OperatingWorkspaceService::notFound);
        requireActionScope(tenant, previous);
        if ("STOCK".equals(previous.kind())) scope.requireGlobalGovernance();
        if (previous.revision() != input.expectedRevision()) throw conflict();
        String status = choice(input.status(), STATUSES, "状态");
        if (!NEXT.get(previous.status()).contains(status)) throw invalid("当前状态不允许此操作");
        var command = new ActionUpdateCommand(text(input.assignee(), 160, true, "负责人"),
                due(input.dueAt()), status, text(input.note(), 2000, true, "处理记录"), input.expectedRevision());
        requireTargetScope(tenant, "SALES_OWNER", command.assignee());
        return store.updateAction(tenant, actor.userId().toString(), previous, command, Instant.now(clock))
                .orElseThrow(OperatingWorkspaceService::conflict);
    }

    public List<ActionEventView> events(String id) {
        var actor = actor(READ);
        String tenant = actor.tenantId().toString();
        String ref = text(id, 64, true, "跟进");
        var action = store.action(tenant, ref).orElseThrow(OperatingWorkspaceService::notFound);
        requireActionScope(tenant, action);
        if ("STOCK".equals(action.kind())) scope.requireGlobalGovernance();
        return store.events(tenant, ref);
    }

    private OperatingWorkspaceStore.BusinessSubject requireSubject(String tenant, String kind, String ref) {
        if ("STOCK".equals(kind)) scope.requireGlobalGovernance();
        var subjects = store.subjects(tenant, kind, ref);
        if (subjects.size() != 1) throw invalid("业务对象不存在或存在歧义，请从业务明细重新打开跟进");
        var subject = subjects.getFirst();
        scope.requireObjectScope(subject.cityCode(), subject.employeeCode());
        return subject;
    }

    private void requireActionScope(String tenant, ActionView action) {
        if ("STOCK".equals(action.kind())) scope.requireGlobalGovernance();
        // 被明确指派的本人可处理此跟进，但不因此取得客户订单/资金的读取权限。
        var access = scope.effective();
        String owner = "SELF".equals(access.accessLevel()) && action.assignee().equals(access.ownerStaffCode())
                ? action.assignee() : action.employeeCode();
        scope.requireObjectScope(action.cityCode(), owner);
        var actual = store.subjects(tenant, action.kind(), action.businessRef());
        if (actual.size() != 1) throw invalid("业务对象不存在或存在歧义，请联系管理员复核");
        scope.requireObjectScope(actual.getFirst().cityCode(),
                "SELF".equals(access.accessLevel()) && action.assignee().equals(access.ownerStaffCode())
                        ? action.assignee() : actual.getFirst().employeeCode());
    }

    private void requireTargetScope(String tenant, String type, String code) {
        var access = scope.effective();
        if ("CITY".equals(type) && "SELF".equals(access.accessLevel())) {
            throw new AuthorizationDeniedException("bi-city-target-write");
        }
        var regions = store.targetRegions(tenant, type, code);
        if (regions.isEmpty()) throw invalid("目标对象尚无可验证的城市/销售归属");
        for (String region : regions) scope.requireObjectScope(region, "SALES_OWNER".equals(type) ? code : null);
    }

    private static CallerIdentity actor(String permission) {
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null || actor.userId() == null) throw new AuthorizationDeniedException("tenant-user");
        AuthorizationContext.requirePermission(READ);
        AuthorizationContext.requirePermission(permission);
        return actor;
    }

    private static YearMonth month(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}")) throw invalid("月份格式应为YYYY-MM");
        try {
            var month = YearMonth.parse(value);
            if (month.getYear() < 2000 || month.getYear() > 2100) throw invalid("目标年份必须在2000至2100之间");
            return month;
        } catch (DateTimeParseException exception) { throw invalid("月份无效"); }
    }

    private static Instant due(Instant value) {
        if (value == null || value.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                || !value.isBefore(Instant.parse("2101-01-01T00:00:00Z"))) throw invalid("请提供有效跟进期限");
        return value;
    }

    private static String optionalChoice(String value, Set<String> choices, String label) {
        return value == null || value.isBlank() ? null : choice(value, choices, label);
    }
    private static String choice(String value, Set<String> choices, String label) {
        if (!choices.contains(value == null ? "" : value)) throw invalid(label + "无效");
        return value;
    }
    private static String text(String value, int limit, boolean required, String label) {
        String result = value == null ? null : value.strip();
        if (result != null && result.isEmpty()) result = null;
        if (required && result == null) throw invalid(label + "不能为空");
        if (result != null && result.length() > limit) throw invalid(label + "过长");
        return result;
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of()); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND, "记录不存在或不可访问", List.of()); }
    public static BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "数据已被其他人修改，请刷新后重新编辑", List.of());
    }
}
