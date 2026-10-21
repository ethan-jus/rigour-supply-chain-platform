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

    public HrPayrollComponentService(
            HrPayrollComponentStore store, HrPayrollStore payroll, HrPayrollSource source) {
        this.store = store;
        this.payroll = payroll;
        this.source = source;
    }

    public List<PayrollComponentVersionView> list(String kind) {
        return list(kind, null);
    }

    public List<PayrollComponentVersionView> list(String kind, String employeeCode) {
        kind(kind);
        var a = actor(permission(kind, false));
        String tenant = a.tenantId().toString();
        return store.versions(tenant).stream()
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
        var old =
                c.previousId() == null
                        ? null
                        : versions.stream()
                                .filter(r -> r.id().equals(c.previousId()))
                                .findFirst()
                                .orElseThrow(() -> bad("原规则不存在"));
        if (old != null) {
            scope(tenant, old.definition(), permission(d.kind(), true));
            if (!old.definition().kind().equals(d.kind())
                    || !"SALARY".equals(d.kind()) && !old.definition().role().equals(d.role())
                    || !new HashSet<>(old.definition().departmentIds())
                            .equals(new HashSet<>(d.departmentIds()))
                    || !Objects.equals(old.definition().employeeCodes(), d.employeeCodes()))
                throw bad("新版本须保留规则类型、身份和适用部门；更换范围请新建规则");
            if (d.effectiveFrom().isBefore(old.definition().effectiveFrom()))
                throw bad("新版本不能早于原版本生效月份");
            if (!d.effectiveFrom().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
                throw bad("已使用规则修订须从未来月份生效");
        }
        if ("ATTENDANCE".equals(d.kind())) {
            List<String> errors = new ArrayList<>();
            PayrollSchemeValidator.calendar(d.calendar(), errors);
            if (!errors.isEmpty()) throw bad(String.join("；", errors));
            for (var other : versions) {
                var p = other.definition();
                if (!"ATTENDANCE".equals(p.kind())
                        || old != null && old.ruleId().equals(other.ruleId())) continue;
                if (!Collections.disjoint(p.departmentIds(), d.departmentIds())
                        && !d.effectiveFrom().isAfter(p.effectiveTo())
                        && !d.effectiveTo().isBefore(p.effectiveFrom()))
                    throw bad("部门已配置工作日历，请从原日历创建新版本");
            }
        }
        if (Set.of("COMMISSION", "ATTENDANCE").contains(d.kind()) && !"ALL".equals(d.role()))
            throw bad("提成和考勤规则统一适用部门内全部身份，提成受益角色在商品标准中维护");
        for (var other : versions) {
            var p = other.definition();
            if (!d.kind().equals(p.kind()) || old != null && old.ruleId().equals(other.ruleId()))
                continue;
            boolean sameScope =
                    "SALARY".equals(d.kind())
                            ? Objects.equals(d.employeeCodes(), p.employeeCodes())
                            : !Collections.disjoint(d.departmentIds(), p.departmentIds())
                                    && (Objects.equals(d.role(), p.role())
                                            || "ALL".equals(d.role())
                                            || "ALL".equals(p.role()));
            if (sameScope
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
            if ("ATTENDANCE".equals(d.kind())
                    && d.departmentIds().contains(department)
                    && !d.effectiveFrom().isAfter(date))
                latest.merge(
                        row.ruleId(),
                        row,
                        (left, right) -> left.version() > right.version() ? left : right);
        }
        latest.values().removeIf(row -> row.definition().effectiveTo().isBefore(date));
        if (latest.size() > 1) throw bad("部门工作日历存在冲突");
        return latest.values().stream().findFirst();
    }

    public boolean unified(String tenant, YearMonth month) {
        var from = store.unifiedFrom(tenant);
        return from != null && month.compareTo(YearMonth.parse(from)) >= 0
                || store.versions(tenant).stream()
                        .anyMatch(r -> !r.definition().effectiveFrom().isAfter(month.atDay(1)));
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
