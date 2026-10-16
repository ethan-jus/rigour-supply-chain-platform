package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.hr.domain.payroll.*;
import com.rigour.order.api.v1.model.OrderPayrollLineView;
import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.math.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ConfiguredPayrollService {
    private final HrPayrollStore payroll;
    private final HrPayrollSchemeStore schemes;
    private final HrPayrollSource source;
    private final HrAttendanceStore attendance;
    private final ConfiguredCommissionService commissions;
    private final ObjectMapper json;

    private HrPayrollComponentService components;
    private TargetSettingsStore targetStore;
    private PayrollRuleResolver rules;

    @org.springframework.beans.factory.annotation.Autowired
    void setRules(PayrollRuleResolver rules) {
        this.rules = rules;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setTargets(TargetSettingsStore targets) {
        this.targetStore = targets;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setComponents(HrPayrollComponentService components) {
        this.components = components;
    }

    public ConfiguredPayrollService(
            HrPayrollStore payroll,
            HrPayrollSchemeStore schemes,
            HrPayrollSource source,
            HrAttendanceStore attendance,
            ConfiguredCommissionService commissions,
            ObjectMapper json) {
        this.payroll = payroll;
        this.schemes = schemes;
        this.source = source;
        this.attendance = attendance;
        this.commissions = commissions;
        this.json = json;
    }

    Optional<PayrollCalculationView> calculateIfConfigured(
            String tenant, PayrollEmployeeView employee, YearMonth month) {
        var versions = schemes.versions(tenant);
        var input = payroll.input(tenant, employee.employeeCode(), month.toString());
        Long department =
                input.departmentOverrideId() != null
                        ? input.departmentOverrideId()
                        : payroll.departmentAt(
                                tenant, employee.employeeCode(), month.atEndOfMonth());
        if (rules == null) throw new IllegalStateException("薪资规则匹配服务未初始化");
        var match = rules.resolve(tenant, employee, department, month);
        if (match.snapshot() == null)
            return Optional.of(pending(tenant, employee, month, String.join("；", match.issues())));
        return Optional.of(calculate(tenant, employee, month, match.snapshot(), versions));
    }

    private PayrollCalculationView pending(
            String tenant, PayrollEmployeeView e, YearMonth month, String issue) {
        return new PayrollCalculationView(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(issue),
                List.of(),
                List.of(),
                payroll.input(tenant, e.employeeCode(), month.toString()),
                hash(List.of(e.employeeCode(), month.toString(), issue)),
                null,
                null,
                null,
                List.of(),
                null);
    }

    public PayrollCalculationView preview(String id, String value, String employeeCode) {
        var actor = HrPayrollService.actor("hr:payroll:calculate");
        String tenant = actor.tenantId().toString();
        HrPayrollService.actor("hr:payroll-rule:read");
        var employee = payroll.employee(tenant, employeeCode, "hr:payroll:calculate");
        var versions = new ArrayList<>(schemes.versions(tenant));
        var draft =
                versions.stream()
                        .filter(v -> v.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("方案不存在"));
        var visibleDepartments =
                payroll.departments(tenant, "hr:payroll-rule:read").stream()
                        .map(PayrollDepartmentView::id)
                        .toList();
        if (!visibleDepartments.containsAll(draft.definition().departmentIds()))
            throw new com.rigour.shared.context.AuthorizationDeniedException(
                    "hr:payroll-rule:read");
        for (String code : draft.definition().employeeCodes())
            payroll.employee(tenant, code, "hr:payroll-rule:read");
        if (!draft.definition().employeeCodes().contains(employeeCode)
                && !draft.definition().departmentIds().contains(employee.departmentId()))
            throw new IllegalArgumentException("员工不在方案适用范围");
        var errors = PayrollSchemeValidator.check(draft.definition()).errors();
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        var month = HrPayrollSchemeService.period(value);
        if (month.atDay(1).isBefore(draft.definition().effectiveFrom())
                || month.atEndOfMonth().isAfter(draft.definition().effectiveTo()))
            throw new IllegalArgumentException("试算月份不在方案生效期内");
        versions.removeIf(v -> v.id().equals(id));
        var simulated =
                new PayrollSchemeVersionView(
                        draft.id(),
                        draft.schemeId(),
                        draft.version(),
                        draft.revision(),
                        "PUBLISHED",
                        draft.definition(),
                        draft.reason(),
                        draft.createdBy(),
                        draft.publishedBy(),
                        draft.updatedTime(),
                        draft.publishedTime(),
                        draft.stopFrom());
        versions.add(simulated);
        return calculate(tenant, employee, month, simulated, versions);
    }

    PayrollCalculationView calculate(
            String tenant,
            PayrollEmployeeView e,
            YearMonth month,
            PayrollSchemeVersionView selected,
            List<PayrollSchemeVersionView> versions) {
        var definition = selected.definition();
        if (components != null && definition.ruleReferences() != null) {
            var override =
                    payroll.input(tenant, e.employeeCode(), month.toString())
                            .departmentOverrideId();
            var department =
                    override != null
                            ? override
                            : payroll.departmentAt(tenant, e.employeeCode(), month.atEndOfMonth());
            var calendarVersion = components.calendar(tenant, department, month.atDay(1));
            if (calendarVersion.isEmpty()) return pending(tenant, e, month, "本月部门工作日历缺失，请在考勤规则中配置");
            definition =
                    definition.withCalendar(
                            calendarVersion.get().definition().calendar(),
                            calendarVersion.get().id());
            selected =
                    new PayrollSchemeVersionView(
                            selected.id(),
                            selected.schemeId(),
                            selected.version(),
                            selected.revision(),
                            selected.status(),
                            definition,
                            selected.reason(),
                            selected.createdBy(),
                            selected.publishedBy(),
                            selected.updatedTime(),
                            selected.publishedTime(),
                            selected.stopFrom());
        }
        List<String> issues = new ArrayList<>(PayrollSchemeValidator.check(definition).errors());
        if (!issues.isEmpty())
            throw new IllegalStateException("已发布薪酬方案不完整：" + String.join("；", issues));
        if (e.entryDate() == null) issues.add("入职日期缺失");
        var input = payroll.input(tenant, e.employeeCode(), month.toString());
        if (month.atDay(1).isBefore(definition.effectiveFrom())
                || month.atEndOfMonth().isAfter(definition.effectiveTo())
                || selected.stopFrom() != null && !month.atDay(1).isBefore(selected.stopFrom()))
            issues.add("薪资规则未覆盖本月，请维护本月生效标准");
        Long departmentBefore = payroll.departmentAt(tenant, e.employeeCode(), month.atDay(1));
        Long departmentAfter = payroll.departmentAt(tenant, e.employeeCode(), month.atEndOfMonth());
        if (input.departmentOverrideId() == null
                && !Objects.equals(departmentBefore, departmentAfter))
            issues.add("月内部门发生变化，请核定当月薪酬适用部门");
        var monthly = schemes.facts(tenant, e.employeeCode(), month.toString());
        var allAssignments =
                payroll.rules(tenant).stream()
                        .filter(
                                r ->
                                        "CITY_MANAGER".equals(r.data().kindCode())
                                                && r.data().enabled())
                        .toList();
        var ownAssignments =
                allAssignments.stream()
                        .filter(r -> e.employeeCode().equals(r.data().employeeCode()))
                        .toList();
        if ("SALES".equals(definition.role())
                && ownAssignments.stream()
                        .anyMatch(
                                r ->
                                        !r.data().effectiveFrom().isAfter(month.atEndOfMonth())
                                                && !r.data()
                                                        .effectiveTo()
                                                        .isBefore(month.atDay(1))))
            issues.add("该员工当月担任城市总，请在员工档案设置城市总薪资类别，避免误领销售补贴");
        Map<String, OrderPayrollLineView> lineMap = new LinkedHashMap<>();
        boolean ordersAvailable = true;
        try {
            source.payrollLines(tenant, e.employeeCode(), null, month.atEndOfMonth())
                    .forEach(f -> lineMap.put(f.lineId(), f));
            for (String city :
                    ownAssignments.stream().map(r -> r.data().cityCode()).distinct().toList())
                source.payrollLines(tenant, null, city, month.atEndOfMonth())
                        .forEach(f -> lineMap.put(f.lineId(), f));
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Payroll order source unavailable", unavailable);
            ordersAvailable = false;
            issues.add("订单付清核算来源暂不可用，不能按零业绩确认工资");
        }
        var facts =
                lineMap.values().stream()
                        .sorted(Comparator.comparing(OrderPayrollLineView::lineId))
                        .toList();
        var own = facts.stream().filter(f -> e.employeeCode().equals(f.employeeCode())).toList();
        BigDecimal achievement =
                ordersAvailable
                        ? achievement(own, month.atDay(1), month.atEndOfMonth(), issues)
                        : null;
        if (input.baseOverride() == null || input.performanceOverride() == null) {
            var legacyOrders =
                    payroll
                            .commissionHistory(
                                    tenant, e.employeeCode(), month.minusMonths(1).toString())
                            .stream()
                            .map(PayrollDetailView::calculation)
                            .filter(c -> c.scheme() == null && c.performanceDetail() != null)
                            .flatMap(c -> c.performanceDetail().sources().stream())
                            .map(PayrollCollectionEvidenceView::orderNo)
                            .collect(java.util.stream.Collectors.toSet());
            if (own.stream()
                    .anyMatch(
                            f ->
                                    f.fullyPaidDate() != null
                                            && YearMonth.from(f.fullyPaidDate()).equals(month)
                                            && legacyOrders.contains(f.orderNo())))
                issues.add("存在旧规则已考核部分回款的存量订单，请核定本月底薪和绩效，避免切换后重复考核");
        }
        BigDecimal performanceBase =
                monthly.performanceBase() == null
                        ? definition.performanceBase()
                        : monthly.performanceBase();
        var calendar = definition.calendar();
        var workDays =
                SalarySchemeCalculator.workDates(
                        month,
                        calendar.weekdays(),
                        calendar.workDays(),
                        calendar.restDays(),
                        calendar.alternateWeekdays() == null
                                ? List.of()
                                : calendar.alternateWeekdays(),
                        calendar.alternateAnchor());
        BigDecimal actualDays = null, expectedDays = null;
        if (List.of(
                        definition.proration().base(),
                        definition.proration().performance(),
                        definition.proration().allowance())
                .contains("CONFIRMED_ATTENDANCE")) {
            var employeeAttendance = attendance.employee(tenant, month, e.employeeCode());
            var confirmed = employeeAttendance == null ? null : employeeAttendance.confirmation();
            if (confirmed == null) issues.add("考勤尚未确认，不能执行出勤折算");
            else {
                actualDays = confirmed.actualDays();
                expectedDays =
                        confirmed.expectedDays() == null
                                ? null
                                : BigDecimal.valueOf(confirmed.expectedDays());
            }
        }
        String tier = null;
        BigDecimal standardBase = definition.baseSalary().fixedAmount();
        if ("TIERS".equals(definition.baseSalary().mode())) {
            boolean first = e.entryDate() != null && YearMonth.from(e.entryDate()).equals(month);
            var resolved =
                    SalarySchemeCalculator.salary(
                            definition.baseSalary().tiers().stream()
                                    .map(
                                            t ->
                                                    new SalaryBand(
                                                            t.code(),
                                                            t.lower(),
                                                            t.upper(),
                                                            t.lowerInclusive(),
                                                            t.upperInclusive(),
                                                            t.amount()))
                                    .toList(),
                            achievement,
                            first ? definition.baseSalary().firstMonthTier() : null);
            standardBase = resolved.amount();
            tier = resolved.code();
            issues.addAll(resolved.issues());
            var tierCode = tier;
            // 档位同时决定底薪和绩效基数；手工核定底薪不应改变绩效档位。
            performanceBase =
                    definition.baseSalary().tiers().stream()
                            .filter(t -> Objects.equals(t.code(), tierCode))
                            .map(PayrollSalaryTier::performanceBase)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
            if (performanceBase == null && tier != null) issues.add("员工档案缺少当月档位的绩效基数");
        }
        BigDecimal base =
                input.baseOverride() == null
                        ? prorate(
                                standardBase,
                                definition.proration().base(),
                                month,
                                e,
                                workDays,
                                actualDays,
                                expectedDays,
                                issues,
                                "底薪")
                        : input.baseOverride();
        BigDecimal performance = input.performanceOverride();
        PayrollPerformanceResultView performanceDetail = null;
        List<PayrollMetricResultView> metricResults = new ArrayList<>();
        BigDecimal managerCoefficient = null, managerCompletion = null;
        int protectedDays = 0, assessedDays = 0;
        List<Object> evidence = new ArrayList<>();
        if (performance == null) {
            switch (definition.performanceMode()) {
                case "NONE" -> performance = BigDecimal.ZERO;
                case "FIXED" ->
                        performance =
                                prorate(
                                        performanceBase,
                                        definition.proration().performance(),
                                        month,
                                        e,
                                        workDays,
                                        actualDays,
                                        expectedDays,
                                        issues,
                                        "绩效");
                case "SALES" -> {
                    var p = definition.sales();
                    long stage =
                            e.entryDate() == null
                                    ? 0
                                    : ChronoUnit.MONTHS.between(
                                                    YearMonth.from(e.entryDate()), month)
                                            + 1;
                    var targets =
                            p.targets().stream()
                                    .filter(
                                            t ->
                                                    stage >= t.fromMonth()
                                                            && (t.toMonth() == null
                                                                    || stage <= t.toMonth()))
                                    .toList();
                    BigDecimal target =
                            targets.size() == 1 ? targets.getFirst().target() : BigDecimal.ONE;
                    if (definition.ruleReferences() != null && targetStore != null) {
                        var explicit =
                                targetStore
                                        .targets(tenant, month.toString(), month.toString())
                                        .stream()
                                        .filter(
                                                v ->
                                                        "SALES_OWNER".equals(v.dimensionType())
                                                                && e.employeeCode().equals(v.code())
                                                                && "FULLY_PAID_RECEIPT_BASIS"
                                                                        .equals(v.metric()))
                                        .findFirst();
                        if (explicit.isPresent()) {
                            evidence.add(explicit.get());
                            target = explicit.get().value();
                            if (!(stage == 1 && p.firstMonthExempt())
                                    && (target == null || target.signum() <= 0)) {
                                issues.add("已核定的薪酬回款目标须大于0");
                                target = BigDecimal.ONE;
                            }
                        }
                    }
                    boolean exempt = stage == 1 && p.firstMonthExempt();
                    if (!exempt && targets.size() != 1) issues.add("入职阶段目标未匹配或冲突");
                    boolean needsCheckins = !exempt && p.processPercent().signum() > 0;
                    var visits = new SalesPayrollEvidenceView(!needsCheckins, List.of());
                    if (needsCheckins)
                        try {
                            visits =
                                    source.performanceEvidence(
                                            tenant,
                                            e.employeeCode(),
                                            month.atDay(1),
                                            month.atEndOfMonth().plusDays(1));
                        } catch (RuntimeException unavailable) {
                            issues.add("打卡拜访依据暂不可用");
                        }
                    evidence.add(visits);
                    var window = calendar.window();
                    List<SalesVisitEvidence> dayVisits = new ArrayList<>();
                    for (var v : visits.visits()) {
                        LocalDate date =
                                v.submittedAt() == null
                                        ? null
                                        : SalarySchemeCalculator.businessDate(
                                                v.submittedAt()
                                                        .atOffset(ZoneOffset.UTC)
                                                        .atZoneSameInstant(
                                                                ZoneId.of("Asia/Shanghai"))
                                                        .toLocalDateTime(),
                                                window.start(),
                                                window.end(),
                                                window.nextDay(),
                                                window.outside());
                        // 无原始时间的记录无法满足当日资格，按未达标处理；来源不可用另行阻断。
                        if (date != null)
                            dayVisits.add(
                                    new SalesVisitEvidence(
                                            v.sourceId(),
                                            date,
                                            v.customerKey(),
                                            v.reviewStatus(),
                                            v.verifiedCommunicationMillis(),
                                            v.durationComplete()));
                    }
                    Set<LocalDate> assessment = new HashSet<>(workDays);
                    assessment.removeAll(calendar.exemptDays());
                    var policy =
                            new SalesPerformancePolicy(
                                    performanceBase,
                                    performanceBase,
                                    exempt ? "EXEMPT_ALL" : "ASSESS",
                                    p.processPercent().intValue(),
                                    p.dailyCheckins(),
                                    p.checkinPenalty(),
                                    p.dailyVisits(),
                                    p.minimumSeconds(),
                                    p.visitPenalty(),
                                    target,
                                    target);
                    var calculated =
                            SalesPerformanceCalculator.calculate(
                                    policy,
                                    month,
                                    e.entryDate(),
                                    e.leaveDate(),
                                    visits.employeeLinked(),
                                    dayVisits,
                                    achievement,
                                    assessment,
                                    p.resultCap(),
                                    p.processPercent(),
                                    p.checkinCountMode());
                    issues.addAll(calculated.issues());
                    performance =
                            prorate(
                                    calculated.total(),
                                    definition.proration().performance(),
                                    month,
                                    e,
                                    workDays,
                                    actualDays,
                                    expectedDays,
                                    issues,
                                    "绩效");
                    performanceDetail =
                            new PayrollPerformanceResultView(
                                    calculated.processBudget(),
                                    calculated.processDeduction(),
                                    calculated.processPay(),
                                    calculated.collectionBudget(),
                                    calculated.collectionTarget(),
                                    achievement,
                                    calculated.collectionPay(),
                                    performance,
                                    calculated.days().stream()
                                            .map(
                                                    d ->
                                                            new PayrollPerformanceDayView(
                                                                    d.date(),
                                                                    d.checkins(),
                                                                    d.effectiveVisits(),
                                                                    d.missingCheckins(),
                                                                    d.missingVisits(),
                                                                    d.deduction(),
                                                                    d.evidenceComplete()))
                                            .toList(),
                                    collectionEvidence(own, month),
                                    calculated.issues());
                }
                case "MANAGER" -> {
                    var policy = definition.manager();
                    var until =
                            SalarySchemeCalculator.protectionUntil(
                                    e.entryDate(),
                                    policy.protection().unit(),
                                    policy.protection().count());
                    LocalDate from =
                            policy.protection().assessUnprotectedOnly()
                                            && until != null
                                            && until.isAfter(month.atDay(1))
                                    ? until
                                    : month.atDay(1);
                    List<ManagerMetric> metrics = new ArrayList<>();
                    for (var metric : policy.metrics()) {
                        var inputMetric =
                                monthly.metrics().stream()
                                        .filter(a -> metric.code().equals(a.code()))
                                        .findFirst()
                                        .orElse(null);
                        BigDecimal actual = null;
                        String proof = "";
                        if ("MANUAL_CONFIRMED".equals(metric.source())) {
                            if (inputMetric != null) {
                                actual = inputMetric.value();
                                proof = inputMetric.evidence();
                            }
                        } else {
                            var scoped =
                                    facts.stream()
                                            .filter(
                                                    f ->
                                                            ConfiguredCommissionService
                                                                            .assignmentsAt(
                                                                                    ownAssignments,
                                                                                    f.cityCode(),
                                                                                    f.orderDate())
                                                                            .size()
                                                                    == 1)
                                            .toList();
                            actual =
                                    !ordersAvailable
                                            ? null
                                            : "FULLY_PAID_RECEIPTS".equals(metric.source())
                                                    ? achievement(
                                                            scoped,
                                                            from,
                                                            month.atEndOfMonth(),
                                                            issues)
                                                    : sales(scoped, from, month.atEndOfMonth());
                            proof = "订单来源，" + from + "至" + month.atEndOfMonth();
                        }
                        BigDecimal target = metric.target();
                        if (definition.ruleReferences() != null && targetStore != null) {
                            var explicit =
                                    targetStore
                                            .targets(tenant, month.toString(), month.toString())
                                            .stream()
                                            .filter(
                                                    v ->
                                                            "SALES_OWNER".equals(v.dimensionType())
                                                                    && e.employeeCode()
                                                                            .equals(v.code())
                                                                    && metric.code()
                                                                            .equals(v.metric()))
                                            .findFirst();
                            target = explicit.map(v -> v.value()).orElse(null);
                            if ((target == null || target.signum() <= 0)
                                    && !from.isAfter(month.atEndOfMonth()))
                                issues.add("请在指标设置核定本人管辖范围的月度目标：" + metric.name());
                            else if (explicit.isPresent()) {
                                evidence.add(explicit.get());
                                proof += "；目标来自指标设置 v" + explicit.get().revision();
                            }
                        }
                        metrics.add(
                                new ManagerMetric(
                                        metric.code(),
                                        metric.name(),
                                        actual,
                                        target,
                                        metric.weight(),
                                        metric.steps().stream()
                                                .map(
                                                        s ->
                                                                new PerformanceBand(
                                                                        s.lower(),
                                                                        s.upper(),
                                                                        s.lowerInclusive(),
                                                                        s.upperInclusive(),
                                                                        s.mode(),
                                                                        s.coefficient()))
                                                .toList(),
                                        metric.cap()));
                        metricResults.add(
                                new PayrollMetricResultView(
                                        metric.code(),
                                        metric.name(),
                                        actual,
                                        target,
                                        metric.weight(),
                                        null,
                                        null,
                                        null,
                                        proof));
                    }
                    var managerProration =
                            "CONFIRMED_ATTENDANCE".equals(definition.proration().performance())
                                    ? "NONE"
                                    : definition.proration().performance();
                    var calculated =
                            SalarySchemeCalculator.manager(
                                    performanceBase,
                                    metrics,
                                    policy.steps().stream()
                                            .map(
                                                    s ->
                                                            new PerformanceBand(
                                                                    s.lower(),
                                                                    s.upper(),
                                                                    s.lowerInclusive(),
                                                                    s.upperInclusive(),
                                                                    s.mode(),
                                                                    s.coefficient()))
                                            .toList(),
                                    policy.cap(),
                                    month,
                                    e.entryDate(),
                                    e.leaveDate(),
                                    until,
                                    policy.protection().assessUnprotectedOnly(),
                                    workDays,
                                    managerProration);
                    issues.addAll(calculated.issues());
                    performance =
                            "CONFIRMED_ATTENDANCE".equals(definition.proration().performance())
                                    ? prorate(
                                            calculated.amount(),
                                            "CONFIRMED_ATTENDANCE",
                                            month,
                                            e,
                                            workDays,
                                            actualDays,
                                            expectedDays,
                                            issues,
                                            "绩效")
                                    : calculated.amount();
                    managerCoefficient = calculated.coefficient();
                    managerCompletion = calculated.weightedCompletion();
                    protectedDays = calculated.protectedDays();
                    assessedDays = calculated.assessedDays();
                    for (int i = 0; i < metricResults.size(); i++) {
                        var old = metricResults.get(i);
                        var result =
                                calculated.metrics().stream()
                                        .filter(r -> old.code().equals(r.code()))
                                        .findFirst()
                                        .orElse(null);
                        if (result != null)
                            metricResults.set(
                                    i,
                                    new PayrollMetricResultView(
                                            old.code(),
                                            old.name(),
                                            old.actual(),
                                            result.target(),
                                            old.weight(),
                                            result.completion(),
                                            result.coefficient(),
                                            result.contribution(),
                                            old.evidence()));
                    }
                }
                default -> issues.add("绩效模式无效");
            }
        }
        BigDecimal allowance = input.allowanceOverride();
        if (allowance == null)
            allowance =
                    prorate(
                            definition.allowances().stream()
                                    .map(PayrollSchemeAllowance::amount)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add),
                            definition.proration().allowance(),
                            month,
                            e,
                            workDays,
                            actualDays,
                            expectedDays,
                            issues,
                            "补贴");
        ConfiguredCommissionResult commission =
                new ConfiguredCommissionResult(
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of());
        boolean commissionAvailable = true;
        try {
            commission =
                    commissions.calculate(
                            tenant, e, month, selected, versions, facts, allAssignments);
            issues.addAll(commission.issues());
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Payroll commission calculation unavailable", unavailable);
            commissionAvailable = false;
            issues.add("提成规则或商品来源暂不可用，请核对后重算");
        }
        var corrections = payroll.corrections(tenant, e.employeeCode(), month.toString());
        var correctionAmount =
                corrections.stream()
                        .map(PayrollCorrectionView::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commissionPay =
                ordersAvailable && commissionAvailable && commission.issues().isEmpty()
                        ? commission.released()
                        : null;
        BigDecimal gross =
                base == null || performance == null || allowance == null || commissionPay == null
                        ? null
                        : SalarySchemeCalculator.money(
                                base.add(performance)
                                        .add(allowance)
                                        .add(commissionPay)
                                        .add(correctionAmount));
        BigDecimal net =
                gross == null
                        ? null
                        : SalarySchemeCalculator.money(gross.subtract(input.deduction()));
        if (net != null && net.signum() < 0) issues.add("更正或扣减后金额为负，请核对后确认");
        if (!month.atEndOfMonth().isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            issues.add("核算月份尚未结束，当前结果仅供试算");
        var snapshot =
                new PayrollSchemeSnapshotView(
                        selected,
                        tier,
                        achievement,
                        performanceBase,
                        List.copyOf(metricResults),
                        managerCoefficient,
                        managerCompletion,
                        protectedDays,
                        assessedDays,
                        List.of(
                                "按员工薪资档案与当月独立规则计算；人工调整单独留痕",
                                "回款业绩按首次整单付清归月",
                                "底薪、绩效与补贴分别执行折算政策",
                                "打卡与有效拜访扣减以该项额度为上限，逐日展示扣减依据"),
                        rules == null
                                ? List.of()
                                : rules.appliedRules(tenant, definition.ruleReferences()));
        var relevantLines =
                facts.stream()
                        .map(OrderPayrollLineView::lineId)
                        .collect(java.util.stream.Collectors.toSet());
        var relevantReleases =
                payroll.released(tenant).stream()
                        .filter(key -> relevantLines.contains(key.split("\\|", 2)[0]))
                        .sorted()
                        .toList();
        String fingerprint =
                hash(
                        Arrays.asList(
                                selected,
                                monthly,
                                input,
                                facts,
                                evidence,
                                allAssignments,
                                relevantReleases,
                                corrections,
                                e,
                                actualDays,
                                expectedDays,
                                commission,
                                base,
                                performance,
                                allowance,
                                achievement,
                                metricResults));
        return new PayrollCalculationView(
                base,
                performance,
                commissionPay,
                commission.held(),
                allowance,
                input.deduction(),
                gross,
                net,
                List.copyOf(new LinkedHashSet<>(issues)),
                commission.details(),
                List.of(),
                input,
                fingerprint,
                performanceDetail,
                payroll.salarySource(tenant, e.employeeCode()),
                correctionAmount,
                corrections,
                snapshot);
    }

    private static BigDecimal prorate(
            BigDecimal amount,
            String mode,
            YearMonth month,
            PayrollEmployeeView e,
            Set<LocalDate> work,
            BigDecimal actual,
            BigDecimal expected,
            List<String> issues,
            String label) {
        var factor =
                SalarySchemeCalculator.proration(
                        mode, month, e.entryDate(), e.leaveDate(), work, actual, expected);
        if (amount == null || factor == null) {
            issues.add(label + "金额或折算依据待核对");
            return null;
        }
        return SalarySchemeCalculator.money(amount.multiply(factor));
    }

    static BigDecimal achievement(
            List<OrderPayrollLineView> facts, LocalDate from, LocalDate to, List<String> issues) {
        Map<String, OrderPayrollLineView> orders = new HashMap<>();
        facts.stream()
                .filter(
                        f ->
                                f.fullyPaidDate() != null
                                        && !f.fullyPaidDate().isBefore(from)
                                        && !f.fullyPaidDate().isAfter(to))
                .forEach(f -> orders.put(f.orderId(), f));
        BigDecimal value = BigDecimal.ZERO;
        boolean complete = true;
        for (var f : orders.values()) {
            if (!"COMPLETE".equals(f.qualityStatus()) || f.orderAmount() == null) {
                issues.add(f.orderNo() + "：付清业绩基数待核定");
                complete = false;
                continue;
            }
            if (f.refundedAmount() != null && f.refundedAmount().signum() > 0)
                issues.add(f.orderNo() + "：退款对业绩及档位的影响需核定，不能按本月新标准扣减");
            value = value.add(f.orderAmount());
        }
        return complete ? SalarySchemeCalculator.money(value) : null;
    }

    private static BigDecimal sales(
            List<OrderPayrollLineView> facts, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> orders = new HashMap<>();
        facts.stream()
                .filter(f -> !f.orderDate().isBefore(from) && !f.orderDate().isAfter(to))
                .forEach(f -> orders.put(f.orderId(), f.orderAmount()));
        return orders.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<PayrollCollectionEvidenceView> collectionEvidence(
            List<OrderPayrollLineView> facts, YearMonth month) {
        Map<String, PayrollCollectionEvidenceView> result = new TreeMap<>();
        for (var f : facts)
            if (f.fullyPaidDate() != null && YearMonth.from(f.fullyPaidDate()).equals(month))
                result.put(
                        f.orderId(),
                        new PayrollCollectionEvidenceView(
                                "FULL:" + f.orderId(),
                                f.orderNo(),
                                f.fullyPaidDate(),
                                f.orderAmount(),
                                "FULLY_PAID"));
        return List.copyOf(result.values());
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(value)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
