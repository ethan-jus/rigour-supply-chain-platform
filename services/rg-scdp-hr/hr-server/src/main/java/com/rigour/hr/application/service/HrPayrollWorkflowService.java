package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrEmployeePayrollProfileStore;
import com.rigour.hr.application.port.out.HrPayrollStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(HrPayrollWorkflowService.class);
    private final HrPayrollStore store;
    private final HrPayrollService payroll;
    private final HrEmployeePayrollProfileStore profiles;

    private final com.rigour.hr.application.port.out.HrPayrollSchemeStore schemes;

    @org.springframework.beans.factory.annotation.Autowired
    public HrPayrollWorkflowService(
            HrPayrollStore store,
            HrPayrollService payroll,
            HrEmployeePayrollProfileStore profiles,
            com.rigour.hr.application.port.out.HrPayrollSchemeStore schemes) {
        this.store = store;
        this.payroll = payroll;
        this.profiles = profiles;
        this.schemes = schemes;
    }

    public HrPayrollWorkflowService(
            HrPayrollStore store,
            HrPayrollService payroll,
            HrEmployeePayrollProfileStore profiles) {
        this.store = store;
        this.payroll = payroll;
        this.profiles = profiles;
        this.schemes = null;
    }

    private HrPayrollComponentService components;
    private PayrollRuleResolver ruleResolver;
    private com.rigour.hr.application.port.out.HrPayrollComponentLifecycleStore ruleReviews;

    @org.springframework.beans.factory.annotation.Autowired
    void setRuleReviews(
            com.rigour.hr.application.port.out.HrPayrollComponentLifecycleStore reviews) {
        this.ruleReviews = reviews;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuleResolver(PayrollRuleResolver rules) {
        this.ruleResolver = rules;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setComponents(HrPayrollComponentService components) {
        this.components = components;
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
        var rows = new ArrayList<PayrollPreparationEmployeeView>();
        var employees = store.employees(tenant, "hr:payroll:read");
        var eligibleEmployees =
                employees.stream()
                        .filter(
                                e ->
                                        e.entryDate() == null
                                                || !e.entryDate().isAfter(m.atEndOfMonth()))
                        .toList();
        var departments =
                ruleResolver == null
                        ? Map.<String, Long>of()
                        : store.preparationDepartments(
                                tenant,
                                eligibleEmployees.stream()
                                        .map(PayrollEmployeeView::employeeCode)
                                        .toList(),
                                value);
        var matches =
                ruleResolver == null
                        ? Map.<String, PayrollRuleMatch>of()
                        : ruleResolver.prepare(
                                tenant,
                                eligibleEmployees,
                                m,
                                e -> departments.get(e.employeeCode()));
        for (var e : employees) {
            if (e.entryDate() != null && e.entryDate().isAfter(m.atEndOfMonth())) continue;
            // 离职人员仍可结算后续付清的订单；生成时只计算提成及更正，不再发固定工资。
            var issues = new ArrayList<String>();
            if (e.entryDate() == null) issues.add("入职日期未维护");
            var match = matches.get(e.employeeCode());
            var ruleIssues = match == null ? List.<String>of() : match.issues();
            var snapshot = match == null ? null : match.snapshot();
            String salaryRole =
                    snapshot == null ? e.payrollCategory() : snapshot.definition().role();
            if (salaryRole == null) issues.add("薪资类别未维护，请在员工档案中设置");
            var p = existing.get(e.employeeCode());
            rows.add(
                    new PayrollPreparationEmployeeView(
                            e.employeeCode(),
                            e.employeeName(),
                            e.departmentId(),
                            e.departmentName(),
                            salaryRole,
                            p == null ? null : p.statusCode(),
                            p == null ? null : p.id(),
                            p != null && Set.of("CONFIRMED", "PAID").contains(p.statusCode()),
                            List.copyOf(issues),
                            List.copyOf(ruleIssues),
                            e.revision(),
                            e.id(),
                            null,
                            null,
                            null,
                            e.entryDate() != null && salaryRole != null && ruleIssues.isEmpty(),
                            true,
                            snapshot == null ? null : snapshot.definition().name(),
                            salaryRole,
                            e.entryDate(),
                            e.leaveDate()));
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
            // Read-only comparison also supports confirmed/paid snapshots. No transitions or
            // writes.
            var saved = store.byMonth(tenant, code, c.month()).orElse(null);
            var calculated = payroll.calculation(tenant, e, m);
            var previous = saved == null ? null : saved.calculation();
            result.add(
                    new PayrollPreviewView(
                            code,
                            e.employeeName(),
                            calculated.issues(),
                            previous != null
                                    && !Objects.equals(
                                            previous.sourceFingerprint(),
                                            calculated.sourceFingerprint()),
                            previous == null ? null : previous.sourceFingerprint(),
                            calculated.sourceFingerprint(),
                            saved == null ? null : saved.statusCode(),
                            calculated,
                            previous));
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
                    throw bad("底薪金额须为非负金额，最多两位小数");
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
        var result = store.reopen(a.tenantId().toString(), id, c, a.principalId().toString());
        log.info(
                "Payroll reopened tenant={} actor={} payrollId={}",
                a.tenantId(),
                a.principalId(),
                id);
        return result;
    }

    @Transactional
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
        String tenant = a.tenantId().toString();
        store.lock(tenant);
        var current = store.detail(tenant, id, "hr:payroll:paid");
        if ("CONFIRMED".equals(current.statusCode())
                && ruleReviews != null
                && ruleReviews.reviews(tenant, "PAYROLL", current.month()).stream()
                        .anyMatch(review -> review.subjectId().equals(id)))
            throw bad("该薪酬存在规则变更待复核任务，请先重新核算确认或登记复核说明后再发放");
        var result = store.pay(a.tenantId().toString(), id, c, a.principalId().toString());
        log.info(
                "Payroll payment registered tenant={} actor={} payrollId={}",
                a.tenantId(),
                a.principalId(),
                id);
        return result;
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
        var result = store.correct(a.tenantId().toString(), id, c, a.principalId().toString());
        log.info(
                "Payroll correction created tenant={} actor={} payrollId={}",
                a.tenantId(),
                a.principalId(),
                id);
        return result;
    }

    public void cancelCorrection(String id, String correctionId, PayrollActionCommand c) {
        var a = actor("hr:payroll:confirm");
        checkAction(c);
        required(c.reason(), 500, "作废原因");
        store.cancelCorrection(
                a.tenantId().toString(), id, correctionId, c, a.principalId().toString());
    }
}
