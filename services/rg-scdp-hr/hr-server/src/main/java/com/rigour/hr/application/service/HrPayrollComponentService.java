package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.actor;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Canonical rule catalog. Payroll matches immutable versions by employee, department and month. */
@Service
public class HrPayrollComponentService {
    private static final Logger log = LoggerFactory.getLogger(HrPayrollComponentService.class);
    private final HrPayrollComponentStore store;
    private final HrPayrollStore payroll;
    private final HrPayrollSource source;
    private final HrPayrollComponentLifecycleStore lifecycle;

    public HrPayrollComponentService(
            HrPayrollComponentStore store,
            HrPayrollStore payroll,
            HrPayrollSource source,
            HrPayrollComponentLifecycleStore lifecycle) {
        this.store = store;
        this.payroll = payroll;
        this.source = source;
        this.lifecycle = lifecycle;
    }

    public List<PayrollComponentVersionView> list(String kind) {
        return list(kind, null);
    }

    public List<PayrollComponentVersionView> list(String kind, String employeeCode) {
        kind(kind);
        var a = actor(permission(kind, false));
        String tenant = a.tenantId().toString();
        return store.versions(tenant).stream()
                .filter(r -> !r.deleted())
                .filter(r -> kind.equals(r.definition().kind()))
                .filter(
                        r ->
                                employeeCode == null
                                        || r.definition().employeeCodes() != null
                                                && r.definition()
                                                        .employeeCodes()
                                                        .contains(employeeCode))
                .filter(
                        r -> {
                            try {
                                scope(tenant, r.definition(), permission(kind, false));
                                return true;
                            } catch (AuthorizationDeniedException denied) {
                                return false;
                            }
                        })
                .toList();
    }

    @Transactional
    public PayrollComponentVersionView save(PayrollComponentSaveCommand c) {
        if (c == null || c.definition() == null) throw bad("规则内容不能为空");
        var d = c.definition();
        kind(d.kind());
        var a = actor(permission(d.kind(), true));
        String tenant = a.tenantId().toString();
        reason(c.reason());
        if (c.requestId() == null || c.requestId().isBlank() || c.requestId().length() > 80)
            throw bad("请求编号无效");
        if (d.name() == null || d.name().isBlank() || d.name().length() > 120)
            throw bad("请填写1至120字规则名称");
        if (d.effectiveFrom() == null
                || d.effectiveTo() == null
                || d.effectiveFrom().isAfter(d.effectiveTo())
                || d.effectiveFrom().getDayOfMonth() != 1
                || d.effectiveTo().getDayOfMonth() != d.effectiveTo().lengthOfMonth())
            throw bad("规则须按完整自然月设置生效期间");
        if (d.role() == null
                || !Set.of("SALES", "CITY_MANAGER", "TRAINEE_MANAGER", "HEADQUARTERS", "ALL")
                        .contains(d.role())) throw bad("人员身份无效");
        if (Set.of("SALARY", "PERFORMANCE", "ALLOWANCE").contains(d.kind())
                && "ALL".equals(d.role())) throw bad("薪资和绩效标准须明确人员身份");
        scope(tenant, d, permission(d.kind(), true));
        payroll.lock(tenant);
        var retry = store.byRequest(tenant, c.requestId());
        if (retry.isPresent()) {
            if (!Objects.equals(retry.get().definition(), c.definition())
                    || !Objects.equals(retry.get().reason(), c.reason()))
                throw bad("请求编号已使用，请刷新后重新提交");
            log.debug(
                    "Payroll rule retry tenant={} requestId={} kind={} versionId={}",
                    tenant,
                    c.requestId(),
                    d.kind(),
                    retry.get().id());
            return retry.get();
        }
        var versions = store.versions(tenant);
        if (d.salary() != null && d.salary().personalPolicy() != null) {
            var id = d.salary().personalPolicy().allowanceVersionId();
            if (id != null && !id.isBlank()) {
                var plan =
                        versions.stream()
                                .filter(
                                        v ->
                                                !v.deleted()
                                                        && v.id().equals(id)
                                                        && "ALLOWANCE"
                                                                .equals(v.definition().kind()))
                                .findFirst()
                                .orElseThrow(() -> bad("关联补贴版本不存在"));
                scope(tenant, plan.definition(), permission("ALLOWANCE", false));
                var department =
                        payroll.departmentAt(
                                tenant, d.employeeCodes().getFirst(), d.effectiveFrom());
                if (!plan.definition().role().equals(d.role())
                        || !plan.enabledForDepartmentAt(department, d.effectiveFrom())
                        || !plan.definition().departmentIds().contains(department)
                        || plan.definition().effectiveFrom().isAfter(d.effectiveFrom())
                        || plan.definition().effectiveTo().isBefore(d.effectiveFrom()))
                    throw bad("请选择与员工身份、任职部门及生效月份一致的补贴版本");
            }
        }
        var old =
                c.previousId() == null
                        ? null
                        : versions.stream()
                                .filter(r -> r.id().equals(c.previousId()))
                                .findFirst()
                                .orElseThrow(() -> bad("原规则不存在"));
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        boolean stoppedRevision =
                old != null
                        && !old.enabledAt(today)
                        && !old.definition().effectiveFrom().isAfter(today)
                        && !old.definition().effectiveTo().isBefore(today);
        if (old != null) {
            scope(tenant, old.definition(), permission(d.kind(), true));
            if (old.deleted()) throw bad("规则已删除，请刷新");
            if (old.retired()) throw bad("规则已废弃，仅可查看和查询操作记录");
            if (c.replacePending()) {
                if (!old.definition()
                                .effectiveFrom()
                                .isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                        || c.revision() == null
                        || c.revision() != old.revision()
                        || lifecycle.inUse(tenant, old.id())) throw bad("仅可修改尚未生效且未被确认结果引用的规则");
                if (!old.definition().kind().equals(d.kind())
                        || !Objects.equals(old.definition().employeeCodes(), d.employeeCodes()))
                    throw bad("不能更换规则类型或员工，请新建规则");
                if (d.effectiveFrom().isBefore(YearMonth.now(ZoneId.of("Asia/Shanghai")).atDay(1)))
                    throw bad("待生效规则修改后不能追溯至历史月份");
            } else {
                if (!old.definition().kind().equals(d.kind())
                        || !"SALARY".equals(d.kind()) && !old.definition().role().equals(d.role())
                        || !stoppedRevision
                                && !new HashSet<>(old.definition().departmentIds())
                                        .equals(new HashSet<>(d.departmentIds()))
                        || !Objects.equals(old.definition().employeeCodes(), d.employeeCodes()))
                    throw bad("新版本须保留规则类型、身份和适用部门；更换范围请新建规则");
                if (!stoppedRevision
                        && d.effectiveFrom().isBefore(old.definition().effectiveFrom()))
                    throw bad("新版本不能早于原版本生效月份");
                if (stoppedRevision) {
                    if (c.revision() == null || c.revision() != old.revision())
                        throw bad("规则已变更，请刷新后重新编辑");
                } else if (!d.effectiveFrom().isAfter(today)) throw bad("已使用规则修订须从未来月份生效");
            }
        }
        if (c.replacePending() && old == null) throw bad("请选择待修改的规则");
        var replacements = new ArrayList<PayrollComponentVersionView>();
        if (old == null) {
            for (var other : versions) {
                var p = other.definition();
                if (!other.deleted()
                        && d.kind().equals(p.kind())
                        && !other.enabledAt(today)
                        && sameScope(d, p)
                        && !d.effectiveFrom().isAfter(p.effectiveTo())
                        && !d.effectiveTo().isBefore(p.effectiveFrom())) replacements.add(other);
            }
        }
        var replacementFamilies =
                replacements.stream()
                        .map(PayrollComponentVersionView::ruleId)
                        .collect(java.util.stream.Collectors.toSet());
        if ("ATTENDANCE".equals(d.kind())) {
            if (d.calendar() != null
                    && ("STATUTORY_SNAPSHOT".equals(d.calendar().source())
                            || d.calendar().referenceVersion() != null))
                throw bad("核算日历快照不能作为考勤规则保存");
            List<String> errors = new ArrayList<>();
            PayrollSchemeValidator.calendar(d.calendar(), errors);
            if (!errors.isEmpty()) throw bad(String.join("；", errors));
            for (var other : versions) {
                var p = other.definition();
                var intersection =
                        p.effectiveFrom().isAfter(d.effectiveFrom())
                                ? p.effectiveFrom()
                                : d.effectiveFrom();
                if (other.deleted()
                        || replacementFamilies.contains(other.ruleId())
                        || d.departmentIds().stream()
                                .noneMatch(
                                        dept ->
                                                p.departmentIds().contains(dept)
                                                        && other.enabledForDepartmentAt(
                                                                dept, intersection))
                        || !other.enabledAt(intersection)
                        || superseded(other, versions, intersection)
                        || !"ATTENDANCE".equals(p.kind())
                        || old != null && old.ruleId().equals(other.ruleId())) continue;
                if (!stoppedRevision
                        && !Collections.disjoint(p.departmentIds(), d.departmentIds())
                        && !d.effectiveFrom().isAfter(p.effectiveTo())
                        && !d.effectiveTo().isBefore(p.effectiveFrom()))
                    throw bad("部门已配置工作日历，请从原日历创建新版本");
            }
        }
        if (Set.of("COMMISSION", "ATTENDANCE").contains(d.kind()) && !"ALL".equals(d.role()))
            throw bad("提成和考勤规则统一适用部门内全部身份，提成受益角色在商品标准中维护");
        for (var other : versions) {
            var p = other.definition();
            var intersection =
                    p.effectiveFrom().isAfter(d.effectiveFrom())
                            ? p.effectiveFrom()
                            : d.effectiveFrom();
            if (other.deleted()
                    || replacementFamilies.contains(other.ruleId())
                    || !enabledInScope(other, d, intersection)
                    || superseded(other, versions, intersection)
                    || !d.kind().equals(p.kind())
                    || old != null && old.ruleId().equals(other.ruleId())) continue;
            if (!stoppedRevision
                    && sameScope(d, p)
                    && !d.effectiveFrom().isAfter(p.effectiveTo())
                    && !d.effectiveTo().isBefore(p.effectiveFrom()))
                throw bad("相同适用范围已存在生效规则，请调整原规则，避免重复计算");
        }
        if ("SALARY".equals(d.kind()) && d.salary() == null
                || "PERFORMANCE".equals(d.kind()) && d.performance() == null
                || "COMMISSION".equals(d.kind()) && d.commission() == null
                || "ALLOWANCE".equals(d.kind()) && d.allowance() == null) throw bad("规则内容缺失");
        if (!"SALARY".equals(d.kind()) && d.salary() != null
                || !"PERFORMANCE".equals(d.kind()) && d.performance() != null
                || !"COMMISSION".equals(d.kind()) && d.commission() != null
                || !"ATTENDANCE".equals(d.kind()) && d.calendar() != null
                || !"ALLOWANCE".equals(d.kind()) && d.allowance() != null)
            throw bad("每个入口只能维护对应的规则内容");
        var check = PayrollSchemeValidator.component(d);
        if (!check.errors().isEmpty()) throw bad(String.join("；", check.errors()));
        if ("COMMISSION".equals(d.kind()))
            PayrollProductReferenceValidator.validate(source, tenant, d.commission());
        var saved = store.save(tenant, c, a.principalId().toString());
        for (var previous : replacements) {
            // The new rule and retirement events commit together under the tenant lock.
            scope(tenant, previous.definition(), permission(d.kind(), true));
            lifecycle.retire(tenant, previous, saved, a.principalId().toString());
        }
        log.info(
                "Payroll rule saved tenant={} actor={} requestId={} kind={} ruleId={} versionId={}"
                        + " version={} effectiveFrom={}",
                tenant,
                a.principalId(),
                c.requestId(),
                d.kind(),
                saved.ruleId(),
                saved.id(),
                saved.version(),
                d.effectiveFrom());
        return saved;
    }

    public Optional<PayrollComponentVersionView> calendar(
            String tenant, Long department, LocalDate date) {
        if (department == null || date == null) return Optional.empty();
        Map<String, PayrollComponentVersionView> latest = new HashMap<>();
        for (var row : store.versions(tenant)) {
            var d = row.definition();
            if (!row.deleted()
                    && "ATTENDANCE".equals(d.kind())
                    && d.departmentIds().contains(department)
                    && !d.effectiveFrom().isAfter(date))
                latest.merge(
                        row.ruleId(),
                        row,
                        (left, right) -> left.version() > right.version() ? left : right);
        }
        latest.values()
                .removeIf(
                        row ->
                                !row.enabledForDepartmentAt(department, date)
                                        || row.definition().effectiveTo().isBefore(date));
        if (latest.size() > 1) throw bad("部门工作日历存在冲突");
        return latest.values().stream().findFirst();
    }

    public boolean unified(String tenant, YearMonth month) {
        var from = store.unifiedFrom(tenant);
        return from != null && month.compareTo(YearMonth.parse(from)) >= 0
                || store.versions(tenant).stream()
                        .anyMatch(
                                r ->
                                        !r.deleted()
                                                && !r.definition()
                                                        .effectiveFrom()
                                                        .isAfter(month.atDay(1)));
    }

    public boolean calendarStopped(String tenant, Long department, LocalDate date) {
        return department != null && stoppedCalendarDepartments(tenant, date).contains(department);
    }

    public Set<Long> stoppedCalendarDepartments(String tenant, LocalDate date) {
        var versions = store.versions(tenant);
        var stopped = new HashSet<Long>();
        var active = new HashSet<Long>();
        for (var row : versions) {
            var d = row.definition();
            if (row.deleted()
                    || !"ATTENDANCE".equals(d.kind())
                    || d.effectiveFrom().isAfter(date)
                    || d.effectiveTo().isBefore(date)
                    || superseded(row, versions, date)) continue;
            for (var department : d.departmentIds())
                (row.enabledForDepartmentAt(department, date) ? active : stopped).add(department);
        }
        stopped.removeAll(active);
        return Set.copyOf(stopped);
    }

    @Transactional
    public PayrollComponentActionResult act(String id, PayrollComponentActionCommand c) {
        if (c == null
                || !Set.of("ENABLE", "DISABLE", "DELETE")
                        .contains(c.action() == null ? "" : c.action())) throw bad("规则操作无效");
        if (c.requestId() == null
                || c.requestId().isBlank()
                || c.requestId().length() > 80
                || c.revision() == null
                || c.revision() < 1
                || c.stateRevision() == null
                || c.stateRevision() < 0) throw bad("请求编号或规则版本无效");
        reason(c.reason());
        var identity = com.rigour.shared.context.AuthorizationContext.requireCurrent();
        if (identity.tenantId() == null)
            throw new AuthorizationDeniedException("hr:payroll-rule:write");
        String tenant = identity.tenantId().toString();
        payroll.lock(tenant);
        var versions = store.versions(tenant);
        var row =
                versions.stream()
                        .filter(r -> r.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> bad("规则不存在"));
        var a = actor(permission(row.definition().kind(), true));
        scope(tenant, row.definition(), permission(row.definition().kind(), true));
        var retry = lifecycle.byRequest(tenant, c.requestId());
        if (retry.isPresent()) {
            var result = retry.get();
            if (!result.rule().id().equals(id)
                    || !result.action().equals(c.action())
                    || !Objects.equals(result.reason(), c.reason())) throw bad("请求编号已使用，请刷新重试");
            return result;
        }
        if (row.deleted()) throw bad("规则已删除，请刷新");
        if (row.retired()) throw bad("规则已废弃，仅可查看和查询操作记录");
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if ("DELETE".equals(c.action())) {
            if (!row.definition().effectiveFrom().isAfter(today) || lifecycle.inUse(tenant, id))
                throw bad("仅可删除尚未生效且未被确认结果引用的规则");
            if (versions.stream()
                    .anyMatch(
                            r ->
                                    !r.deleted()
                                            && r.ruleId().equals(row.ruleId())
                                            && r.version() > row.version()))
                throw bad("仅可删除最新的待生效版本");
        } else {
            var current =
                    versions.stream()
                            .filter(
                                    r ->
                                            !r.deleted()
                                                    && r.ruleId().equals(row.ruleId())
                                                    && !r.definition()
                                                            .effectiveFrom()
                                                            .isAfter(today))
                            .max(Comparator.comparingInt(PayrollComponentVersionView::version))
                            .orElse(null);
            if (current == null
                    || !current.id().equals(id)
                    || row.definition().effectiveTo().isBefore(today))
                throw bad("仅可停用或重新启用当前有效期间内的规则");
            if ("ENABLE".equals(c.action()) == row.enabledAt(today)) throw bad("规则已处于目标状态，请刷新");
            if ("ENABLE".equals(c.action())) validateEnable(row, versions, today);
        }
        var result = lifecycle.act(tenant, row, c, a.principalId().toString(), today);
        log.info(
                "Payroll rule action tenant={} actor={} ruleId={} versionId={} action={}"
                        + " requestId={} attendanceReviews={} payrollReviews={}",
                tenant,
                a.principalId(),
                row.ruleId(),
                id,
                c.action(),
                c.requestId(),
                result.attendanceReviews(),
                result.payrollReviews());
        return result;
    }

    private void validateEnable(
            PayrollComponentVersionView row,
            List<PayrollComponentVersionView> versions,
            LocalDate today) {
        var d = row.definition();
        for (var other : versions) {
            var p = other.definition();
            var intersection = p.effectiveFrom().isAfter(today) ? p.effectiveFrom() : today;
            if (other.deleted()
                    || row.ruleId().equals(other.ruleId())
                    || !d.kind().equals(p.kind())
                    || !enabledInScope(other, d, intersection)
                    || superseded(other, versions, intersection)
                    || p.effectiveTo().isBefore(today)
                    || p.effectiveFrom().isAfter(d.effectiveTo())) continue;
            if (sameScope(d, p)) throw bad("相同范围已有其他规则，重新启用会导致重复计算，请先处理冲突规则");
        }
    }

    private static boolean sameScope(
            PayrollComponentDefinition left, PayrollComponentDefinition right) {
        if (!left.kind().equals(right.kind())) return false;
        return "SALARY".equals(left.kind())
                ? !Collections.disjoint(left.employeeCodes(), right.employeeCodes())
                : !Collections.disjoint(left.departmentIds(), right.departmentIds())
                        && (left.role().equals(right.role())
                                || "ALL".equals(left.role())
                                || "ALL".equals(right.role()));
    }

    private static boolean enabledInScope(
            PayrollComponentVersionView row, PayrollComponentDefinition scope, LocalDate date) {
        if (!sameScope(scope, row.definition())) return false;
        return "SALARY".equals(scope.kind())
                ? scope.employeeCodes().stream()
                        .anyMatch(employee -> row.enabledForEmployeeAt(employee, date))
                : scope.departmentIds().stream()
                        .anyMatch(
                                dept ->
                                        row.definition().departmentIds().contains(dept)
                                                && row.enabledForDepartmentAt(dept, date));
    }

    private static boolean superseded(
            PayrollComponentVersionView row,
            List<PayrollComponentVersionView> versions,
            LocalDate date) {
        return versions.stream()
                .anyMatch(
                        other ->
                                !other.deleted()
                                        && other.ruleId().equals(row.ruleId())
                                        && other.version() > row.version()
                                        && !other.definition().effectiveFrom().isAfter(date));
    }

    public List<PayrollComponentOperationView> operations(String id) {
        var identity = com.rigour.shared.context.AuthorizationContext.requireCurrent();
        if (identity.tenantId() == null)
            throw new AuthorizationDeniedException("hr:payroll-rule:read");
        String tenant = identity.tenantId().toString();
        var row =
                store.versions(tenant).stream()
                        .filter(r -> r.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> bad("规则不存在"));
        actor(permission(row.definition().kind(), false));
        scope(tenant, row.definition(), permission(row.definition().kind(), false));
        return lifecycle.operations(tenant, row.ruleId());
    }

    private void scope(String tenant, PayrollComponentDefinition d, String action) {
        if ("SALARY".equals(d.kind())) {
            if (d.employeeCodes() == null
                    || d.employeeCodes().size() != 1
                    || d.employeeCodes().getFirst() == null
                    || d.departmentIds() == null
                    || !d.departmentIds().isEmpty()) throw bad("薪资标准须在单个员工档案内维护");
            payroll.employee(tenant, d.employeeCodes().getFirst(), action);
            return;
        }
        if (d.employeeCodes() != null && !d.employeeCodes().isEmpty())
            throw bad("公共规则按部门配置，个人薪资请在员工档案维护");
        if (d.departmentIds() == null
                || d.departmentIds().isEmpty()
                || d.departmentIds().stream().anyMatch(Objects::isNull)) throw bad("请选择适用部门");
        var visible =
                payroll.departments(tenant, action).stream()
                        .map(PayrollDepartmentView::id)
                        .toList();
        if (!visible.containsAll(d.departmentIds())) throw new AuthorizationDeniedException(action);
    }

    private static String permission(String kind, boolean write) {
        // Employee directory access must not implicitly expose salary amounts.
        return ("ATTENDANCE".equals(kind) ? "hr:attendance-rule:" : "hr:payroll-rule:")
                + (write ? "write" : "read");
    }

    private static void kind(String kind) {
        if (kind == null
                || !Set.of("SALARY", "PERFORMANCE", "COMMISSION", "ATTENDANCE", "ALLOWANCE")
                        .contains(kind)) throw bad("规则类型无效");
    }

    private static void reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw bad("请填写1至1000字变更原因");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
