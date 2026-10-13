package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrEmployeePayrollProfileStore;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.hr.domain.payroll.EmployeeSalaryResolver;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 核算前检查、人员准备与确认后的处理；所有工资变化经仓储事务和审计。 */
@Service
public class HrPayrollWorkflowService {
    private final HrPayrollStore store;
    private final HrPayrollService payroll;
    private final HrEmployeePayrollProfileStore profiles;

    public HrPayrollWorkflowService(
            HrPayrollStore store,
            HrPayrollService payroll,
            HrEmployeePayrollProfileStore profiles) {
        this.store = store;
        this.payroll = payroll;
        this.profiles = profiles;
    }

    public PayrollPreparationView preparation(String value) {
        var a = actor("hr:payroll:read");
        var m = month(value);
        String tenant = a.tenantId().toString();
        var existing =
                store.monthRecords(tenant, value, "hr:payroll:read").stream()
                        .collect(
                                Collectors.toMap(
                                        PayrollDetailView::employeeCode, Function.identity()));
        var rules = store.rules(tenant);
        var rows = new ArrayList<PayrollPreparationEmployeeView>();
        for (var e : store.employees(tenant, "hr:payroll:read")) {
            boolean outsideEmployment =
                    e.entryDate() != null && e.entryDate().isAfter(m.atEndOfMonth())
                            || e.leaveDate() != null && e.leaveDate().isBefore(m.atDay(1));
            boolean pendingCorrection =
                    outsideEmployment
                            && !store.corrections(tenant, e.employeeCode(), value).isEmpty();
            // 已离职员工仍可能有跨月补差，不能从目标月份的操作名单消失。
            if (!existing.containsKey(e.employeeCode()) && outsideEmployment && !pendingCorrection)
                continue;
            var issues = new ArrayList<String>();
            if (pendingCorrection) issues.add("非在职月份有补差，请在月度调整核定底薪、绩效、补贴；无收入项填写0");
            var ruleIssues = new ArrayList<String>();
            if (e.payrollCategory() == null || e.payrollCategory().isBlank()) issues.add("未设置薪酬类别");
            var salary = store.salarySource(tenant, e.employeeCode());
            if ("SALES".equals(e.payrollCategory())) {
                if (e.entryDate() == null) issues.add("未填写入职日期");
                if (e.departmentId() == null) issues.add("未关联部门");
                if (salary == null) issues.add("薪资档案缺失");
                else
                    EmployeeSalaryResolver.resolve(
                            m,
                            salary.regularized(),
                            salary.regularizationDate(),
                            salary.effectiveFrom(),
                            salary.regularSalary(),
                            salary.probationSalary(),
                            issues);
                var applicable =
                        rules.stream()
                                .filter(
                                        r ->
                                                r.data().enabled()
                                                        && r.data()
                                                                .departmentIds()
                                                                .contains(e.departmentId())
                                                        && !r.data()
                                                                .effectiveFrom()
                                                                .isAfter(m.atEndOfMonth())
                                                        && !r.data()
                                                                .effectiveTo()
                                                                .isBefore(m.atDay(1)))
                                .toList();
                var performance =
                        applicable.stream()
                                .filter(r -> "PERFORMANCE".equals(r.data().kindCode()))
                                .toList();
                if (performance.size() != 1) ruleIssues.add("绩效规则缺失或冲突，可在月度调整核定");
                else if (performance.getFirst().data().effectiveFrom().isAfter(m.atDay(1))
                        || performance.getFirst().data().effectiveTo().isBefore(m.atEndOfMonth()))
                    ruleIssues.add("绩效规则未覆盖整月");
                if (applicable.stream().noneMatch(r -> "ALLOWANCE".equals(r.data().kindCode())))
                    ruleIssues.add("未配置补贴规则，请确认是否不发补贴");
                if (applicable.stream()
                        .anyMatch(
                                r ->
                                        "ALLOWANCE".equals(r.data().kindCode())
                                                && (r.data().effectiveFrom().isAfter(m.atDay(1))
                                                        || r.data()
                                                                .effectiveTo()
                                                                .isBefore(m.atEndOfMonth()))))
                    ruleIssues.add("补贴月内生效或结束，需当月核定");
                if (applicable.stream().noneMatch(r -> "COMMISSION".equals(r.data().kindCode())))
                    ruleIssues.add("未配置适用商品提成；未配置商品不计提");
            }
            var p = existing.get(e.employeeCode());
            rows.add(
                    new PayrollPreparationEmployeeView(
                            e.employeeCode(),
                            e.employeeName(),
                            e.departmentId(),
                            e.departmentName(),
                            e.payrollCategory(),
                            p == null ? null : p.statusCode(),
                            p == null ? null : p.id(),
                            p != null && Set.of("CONFIRMED", "PAID").contains(p.statusCode()),
                            List.copyOf(issues),
                            List.copyOf(ruleIssues),
                            e.revision(),
                            e.id(),
                            salary == null ? null : salary.regularized(),
                            salary == null ? null : salary.regularizationDate(),
                            salary == null ? null : salary.effectiveFrom()));
        }
        return new PayrollPreparationView(value, List.copyOf(rows));
    }

    public List<PayrollPreviewView> preview(PayrollCalculateCommand c) {
        var a = actor("hr:payroll:calculate");
        if (c == null
                || c.employeeCodes() == null
                || c.employeeCodes().isEmpty()
                || c.employeeCodes().size() > 100
                || c.employeeCodes().stream().anyMatch(Objects::isNull))
            throw bad("每批请选择1至100名员工检查");
        var m = month(c.month());
        String tenant = a.tenantId().toString();
        var result = new ArrayList<PayrollPreviewView>();
        for (var code : new LinkedHashSet<>(c.employeeCodes())) {
            var e = store.employee(tenant, code, "hr:payroll:calculate");
            if (store.byMonth(tenant, code, c.month())
                    .map(p -> Set.of("CONFIRMED", "PAID").contains(p.statusCode()))
                    .orElse(false))
                result.add(
                        new PayrollPreviewView(code, e.employeeName(), List.of("已确认或已发放，跳过检查与重算")));
            else
                result.add(
                        new PayrollPreviewView(
                                code,
                                e.employeeName(),
                                payroll.calculation(tenant, e, m).issues()));
        }
        return List.copyOf(result);
    }

    @Transactional
    public void updateProfiles(PayrollBatchProfileCommand c) {
        var a = actor("hr:employee:update");
        if (c == null
                || c.employees() == null
                || c.employees().isEmpty()
                || c.employees().size() > 100) throw bad("每次可维护1至100名员工");
        required(c.reason(), 500, "维护原因");
        var codes = new HashSet<String>();
        for (var p : c.employees()) {
            if (p == null) throw bad("员工信息不能为空");
            required(p.employeeCode(), 50, "员工编号");
            revision(p.revision());
            if (!codes.add(p.employeeCode())) throw bad("同一批次不能重复选择员工");
            if (p.payrollCategory() != null
                    && !Set.of("SALES", "HEADQUARTERS").contains(p.payrollCategory()))
                throw bad("薪酬类别无效");
            if (p.regularized() == null && p.regularizationDate() != null
                    || Boolean.FALSE.equals(p.regularized()) && p.regularizationDate() != null)
                throw bad("仅已转正员工可填写转正日期");
            if (Boolean.TRUE.equals(p.regularized()) && p.regularizationDate() == null)
                throw bad("请填写转正日期");
            if (p.regularizationDate() != null
                    && p.regularizationDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
                throw bad("转正日期不能晚于今天");
            for (String salary : new String[] {p.probationSalary(), p.regularSalary()}) {
                if (salary != null && !salary.matches("[0-9]{1,14}([.][0-9]{1,2})?"))
                    throw bad("销售工资须为非负金额，最多两位小数");
            }
            if (p.payrollCategory() == null
                    && p.regularized() == null
                    && p.salaryEffectiveFrom() == null
                    && p.probationSalary() == null
                    && p.regularSalary() == null) throw bad("请选择需要维护的字段");
        }
        String tenant = a.tenantId().toString();
        store.lock(tenant);
        profiles.update(tenant, c, a.principalId().toString());
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<PayrollDetailView> export(PayrollQuery q) {
        var a = actor("hr:payroll:read");
        month(q.month());
        return store.export(a.tenantId().toString(), q, "hr:payroll:read");
    }

    public PayrollDetailView reopen(String id, PayrollActionCommand c) {
        var a = actor("hr:payroll:confirm");
        checkAction(c);
        required(c.reason(), 500, "撤回原因");
        return store.reopen(a.tenantId().toString(), id, c, a.principalId().toString());
    }

    public PayrollDetailView pay(String id, PayrollPaymentCommand c) {
        var a = actor("hr:payroll:paid");
        if (c == null) throw bad("缺少发放信息");
        request(c.requestId());
        revision(c.revision());
        money(c.amount(), true);
        required(c.referenceNo(), 128, "凭证号");
        required(c.reason(), 500, "发放说明");
        if (c.paidDate() == null || c.paidDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw bad("请填写不晚于今天的实际发放日期");
        return store.pay(a.tenantId().toString(), id, c, a.principalId().toString());
    }

    public PayrollCorrectionView correct(String id, PayrollCorrectionCommand c) {
        var a = actor("hr:payroll:confirm");
        if (c == null) throw bad("缺少补差信息");
        request(c.requestId());
        revision(c.revision());
        month(c.targetMonth());
        if (c.amount() == null || c.amount().signum() == 0) throw bad("补差金额不能为0，正数补发、负数冲回");
        money(c.amount().abs(), true);
        required(c.reason(), 500, "补差原因");
        return store.correct(a.tenantId().toString(), id, c, a.principalId().toString());
    }

    public void cancelCorrection(String id, String correctionId, PayrollActionCommand c) {
        var a = actor("hr:payroll:confirm");
        checkAction(c);
        required(c.reason(), 500, "作废原因");
        store.cancelCorrection(
                a.tenantId().toString(), id, correctionId, c, a.principalId().toString());
    }
}
