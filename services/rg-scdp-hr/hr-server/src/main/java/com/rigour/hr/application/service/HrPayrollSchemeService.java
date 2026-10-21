package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.actor;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollSchemeStore;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

@Service
public class HrPayrollSchemeService {
    private final HrPayrollSchemeStore store;
    private final HrPayrollStore payroll;
    private final com.rigour.hr.application.port.out.HrPayrollSource source;

    private HrPayrollComponentService components;

    @org.springframework.beans.factory.annotation.Autowired
    void setComponents(HrPayrollComponentService components) {
        this.components = components;
    }

    public HrPayrollSchemeService(
            HrPayrollSchemeStore store,
            HrPayrollStore payroll,
            com.rigour.hr.application.port.out.HrPayrollSource source) {
        this.store = store;
        this.payroll = payroll;
        this.source = source;
    }

    public List<PayrollSchemeVersionView> list() {
        String tenant = actor("hr:payroll-rule:read").tenantId().toString();
        return store.versions(tenant).stream()
                .filter(
                        v -> {
                            try {
                                scope(tenant, v.definition(), "hr:payroll-rule:read");
                                return true;
                            } catch (AuthorizationDeniedException | BusinessException denied) {
                                return false;
                            }
                        })
                .toList();
    }

    public PayrollSchemeCheckView validate(PayrollSchemeDefinition definition) {
        var a = actor("hr:payroll-rule:write");
        scope(a.tenantId().toString(), definition, "hr:payroll-rule:write");
        requireLegacyConfiguration(a.tenantId().toString());
        return PayrollSchemeValidator.check(definition);
    }

    public PayrollSchemeVersionView require(String tenant, String id, String permission) {
        var result =
                store.versions(tenant).stream()
                        .filter(v -> v.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> bad("方案不存在或不在当前租户"));
        scope(tenant, result.definition(), permission);
        return result;
    }

    @Transactional
    public PayrollSchemeVersionView save(String id, PayrollSchemeSaveCommand command) {
        var a = actor("hr:payroll-rule:write");
        String tenant = a.tenantId().toString();
        if (command == null
                || command.requestId() == null
                || command.requestId().isBlank()
                || command.requestId().length() > 80) throw bad("请求编号无效");
        reason(command.reason());
        scope(tenant, command.definition(), "hr:payroll-rule:write");
        if (id != null) require(tenant, id, "hr:payroll-rule:write");
        if (command.schemeId() != null) {
            var chain =
                    store.versions(tenant).stream()
                            .filter(v -> command.schemeId().equals(v.schemeId()))
                            .toList();
            if (chain.isEmpty()) throw bad("原方案不存在");
            chain.forEach(v -> scope(tenant, v.definition(), "hr:payroll-rule:write"));
            var original = chain.getFirst().definition();
            if (!sameScope(original, command.definition())) throw bad("新版本须保留原适用范围；更换范围请复制为独立方案");
        }
        requireLegacyConfiguration(tenant);
        var resolved = command.definition();
        return store.save(
                tenant,
                id,
                new PayrollSchemeSaveCommand(
                        command.requestId(),
                        command.revision(),
                        command.schemeId(),
                        resolved,
                        command.reason()),
                a.principalId().toString());
    }

    @Transactional
    public PayrollSchemeVersionView publish(String id, PayrollSchemePublishCommand command) {
        var a = actor("hr:payroll-rule:publish");
        String tenant = a.tenantId().toString();
        if (command == null || command.revision() < 0) throw bad("发布版本不能为空");
        reason(command.reason());
        payroll.lock(tenant);
        requireLegacyConfiguration(tenant);
        var draft = require(tenant, id, "hr:payroll-rule:publish");
        var check = PayrollSchemeValidator.check(draft.definition());
        if (!check.errors().isEmpty()) throw bad(String.join("；", check.errors()));
        var d = draft.definition();
        verifyProductReferences(tenant, d);
        if (!d.effectiveFrom()
                .isAfter(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))) {
            for (String employee : d.employeeCodes())
                if (payroll
                        .commissionHistory(
                                tenant,
                                employee,
                                YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).toString())
                        .stream()
                        .anyMatch(row -> row.calculation().scheme() != null))
                    throw bad("员工已有按方案确认的工资，新员工专项方案须从未来月份生效，历史差异通过工资更正处理");
        }
        for (var old : store.versions(tenant)) {
            if (!"PUBLISHED".equals(old.status()) || old.id().equals(id)) continue;
            var p = old.definition();
            if (old.schemeId().equals(draft.schemeId())) {
                if (!sameScope(p, d)) throw bad("同一方案的版本适用范围不可改变");
                if (!d.effectiveFrom()
                        .isAfter(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
                    throw bad("规则变更从未来月份生效，历史核算请使用工资更正");
                if (!d.effectiveFrom().isAfter(p.effectiveFrom())) throw bad("新版本开始月份必须晚于已发布版本");
            } else if (!d.effectiveFrom().isAfter(p.effectiveTo())
                    && !d.effectiveTo().isBefore(p.effectiveFrom())
                    && (old.stopFrom() == null || d.effectiveFrom().isBefore(old.stopFrom()))
                    && (!Collections.disjoint(d.departmentIds(), p.departmentIds())
                            || !Collections.disjoint(d.employeeCodes(), p.employeeCodes())))
                throw bad("与已发布方案存在同级适用范围冲突：" + p.name());
        }
        return store.publish(tenant, id, command, a.principalId().toString());
    }

    @Transactional
    public PayrollSchemeVersionView stop(String id, PayrollSchemeStopCommand command) {
        var a = actor("hr:payroll-rule:publish");
        String tenant = a.tenantId().toString();
        if (command == null || command.stopFrom() == null || command.revision() < 0)
            throw bad("请填写停用月份与版本");
        reason(command.reason());
        if (command.stopFrom().getDayOfMonth() != 1
                || !command.stopFrom()
                        .isAfter(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
            throw bad("停用从未来月份生效，不能修改历史工资");
        payroll.lock(tenant);
        require(tenant, id, "hr:payroll-rule:publish");
        return store.stop(tenant, id, command, a.principalId().toString());
    }

    public PayrollSchemeFactsView facts(String month, String employee) {
        var a = actor("hr:payroll:read");
        period(month);
        payroll.employee(a.tenantId().toString(), employee, "hr:payroll:read");
        return store.facts(a.tenantId().toString(), employee, month);
    }

    @Transactional
    public PayrollSchemeFactsView saveFacts(
            String month, String employee, PayrollSchemeFactsCommand command) {
        var a = actor("hr:payroll:calculate");
        String tenant = a.tenantId().toString();
        period(month);
        payroll.employee(tenant, employee, "hr:payroll:calculate");
        payroll.lock(tenant);
        if (payroll.byMonth(tenant, employee, month)
                .map(r -> Set.of("CONFIRMED", "PAID").contains(r.statusCode()))
                .orElse(false)) throw bad("已确认月份不能改指标，请走工资更正");
        if (command == null) throw bad("核定内容不能为空");
        reason(command.reason());
        if (command.performanceBase() != null && command.performanceBase().signum() < 0)
            throw bad("绩效基数不能为负数");
        if (command.metrics() == null) throw bad("指标列表不能为空");
        Set<String> codes = new HashSet<>();
        for (var actual : command.metrics()) {
            if (actual == null
                    || actual.code() == null
                    || actual.code().isBlank()
                    || !codes.add(actual.code())
                    || actual.value() == null
                    || actual.value().signum() < 0
                    || actual.evidence() == null
                    || actual.evidence().isBlank()) throw bad("指标须有唯一编码、非负实际值及核定依据");
        }
        return store.saveFacts(tenant, employee, month, command, a.principalId().toString());
    }

    private void verifyProductReferences(String tenant, PayrollSchemeDefinition definition) {
        PayrollProductReferenceValidator.validate(source, tenant, definition.commission());
    }

    private void requireLegacyConfiguration(String tenant) {
        if (components != null && components.unified(tenant, YearMonth.of(2200, 12)))
            throw bad("已使用独立薪资规则，请在员工档案和考勤、绩效、补贴、提成入口维护");
    }

    private void scope(String tenant, PayrollSchemeDefinition d, String permission) {
        if (d == null
                || d.departmentIds() == null
                || d.employeeCodes() == null
                || d.departmentIds().isEmpty() && d.employeeCodes().isEmpty())
            throw bad("请选择适用部门或员工");
        var visible =
                payroll.departments(tenant, permission).stream()
                        .map(PayrollDepartmentView::id)
                        .toList();
        if (!visible.containsAll(d.departmentIds()))
            throw new AuthorizationDeniedException(permission);
        for (String employee : d.employeeCodes()) payroll.employee(tenant, employee, permission);
    }

    private static boolean sameScope(PayrollSchemeDefinition a, PayrollSchemeDefinition b) {
        return Objects.equals(a.role(), b.role())
                && new HashSet<>(a.departmentIds()).equals(new HashSet<>(b.departmentIds()))
                && new HashSet<>(a.employeeCodes()).equals(new HashSet<>(b.employeeCodes()));
    }

    static YearMonth period(String value) {
        try {
            var month = YearMonth.parse(value);
            if (month.getYear() < 2000 || month.getYear() > 2200) throw bad("月份超出范围");
            return month;
        } catch (java.time.DateTimeException | NullPointerException e) {
            throw bad("月份格式应为yyyy-MM");
        }
    }

    private static void reason(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) throw bad("请填写1至1000字的变更原因");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
