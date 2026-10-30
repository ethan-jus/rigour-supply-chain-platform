package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.AttendanceDepartmentOption;
import com.rigour.hr.api.v1.model.AttendanceExceptionPeriod;
import com.rigour.hr.api.v1.model.AttendanceRecordCommand;
import com.rigour.hr.api.v1.model.AttendanceReopenCommand;
import com.rigour.hr.api.v1.model.AttendanceRow;
import com.rigour.hr.api.v1.model.AttendanceRuleCommand;
import com.rigour.hr.api.v1.model.AttendanceRuleListRow;
import com.rigour.hr.api.v1.model.AttendanceRuleListView;
import com.rigour.hr.api.v1.model.AttendanceRuleView;
import com.rigour.hr.api.v1.model.AttendanceView;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.application.port.out.HrAttendanceSource;
import com.rigour.hr.application.port.out.HrAttendanceStore;
import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HrAttendanceService {
    public static final String READ = "hr:attendance:read",
            WRITE = "hr:attendance:write",
            RULE_READ = "hr:attendance-rule:read",
            RULE_WRITE = "hr:attendance-rule:write";
    public static final String PENDING = "PENDING", CONFIRMED = "CONFIRMED";
    private final HrAttendanceStore store;
    private final HrAttendanceSource source;
    private final Clock clock;

    private final PayrollAttendanceEvidenceService payrollAttendance;
    private com.rigour.hr.application.port.out.HrPayrollComponentLifecycleStore ruleReviews;

    @org.springframework.beans.factory.annotation.Autowired
    void setRuleReviews(
            com.rigour.hr.application.port.out.HrPayrollComponentLifecycleStore reviews) {
        this.ruleReviews = reviews;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HrAttendanceService(
            HrAttendanceStore store,
            HrAttendanceSource source,
            Clock clock,
            PayrollAttendanceEvidenceService payrollAttendance) {
        this.store = store;
        this.source = source;
        this.clock = clock;
        this.payrollAttendance = payrollAttendance;
    }

    public HrAttendanceService(HrAttendanceStore store, HrAttendanceSource source, Clock clock) {
        this.store = store;
        this.source = source;
        this.clock = clock;
        this.payrollAttendance = null;
    }

    public AttendanceRuleView rule(String month, Long departmentId) {
        var actor = actor(RULE_READ);
        var period = month(month);
        String tenant = actor.tenantId().toString();
        store.requireRuleScope(tenant, departmentId, RULE_READ);
        validateDepartment(tenant, departmentId);
        var rule = store.rule(tenant, month, departmentId);
        Integer calendarDays = null;
        String calendarMessage = null;
        if (payrollAttendance != null && departmentId != null) {
            if (payrollAttendance.stopped(tenant, departmentId, period)) {
                calendarMessage = "本部门薪资考勤规则已停用，请先启用或配置替代规则";
            } else {
                try {
                    calendarDays =
                            payrollAttendance
                                    .expectedDays(tenant, departmentId, period)
                                    .orElse(null);
                    calendarMessage =
                            calendarDays == null ? "未匹配到生效的薪资考勤规则，可手工补录" : "已按本部门生效的薪资考勤规则计算";
                } catch (BusinessException unavailable) {
                    calendarMessage = unavailable.getMessage();
                }
            }
        }
        return new AttendanceRuleView(
                rule.month(),
                rule.departmentId(),
                rule.expectedDays(),
                rule.revision(),
                rule.companyExpectedDays(),
                rule.locked(),
                store.ruleWritable(tenant, departmentId),
                calendarDays,
                calendarMessage, rule.createdBy(), rule.createdByName(), rule.createdTime());
    }

    public AttendanceRuleListView rules(
            String value, Long departmentId, String status, int begin, int step) {
        var actor = actor(RULE_READ);
        month(value);
        if (departmentId != null && departmentId < 0) throw bad("部门无效");
        String selectedStatus = status == null ? "" : status.trim();
        if (!Set.of("", "LOCKED", "UNLOCKED").contains(selectedStatus)) throw bad("规则状态无效");
        if (begin < 0 || step < 1 || step > 100) throw bad("分页范围无效");
        String tenant = actor.tenantId().toString();
        if (departmentId != null && departmentId > 0)
            store.requireRuleScope(tenant, departmentId, RULE_READ);
        var overview = store.ruleOverview(tenant, value);
        var departments =
                store.departments(tenant).stream()
                        .sorted(
                                Comparator.comparingInt(AttendanceDepartmentOption::sortOrder)
                                        .thenComparing(AttendanceDepartmentOption::id))
                        .toList();
        var names = new HashMap<Long, String>();
        departments.forEach(d -> names.put(d.id(), d.departmentName()));
        // 按组织树的父子顺序展示；已移除的部门规则仍可查看历史内容。
        var order = new LinkedHashMap<Long, Integer>();
        order.put(0L, 0);
        for (var department : departments) {
            if (department.parentId() == null || !names.containsKey(department.parentId()))
                appendDepartmentOrder(department.id(), departments, order);
        }
        departments.forEach(d -> appendDepartmentOrder(d.id(), departments, order));
        var records =
                overview.rules().stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (HrAttendanceStore.RuleRecord r) ->
                                                        order.getOrDefault(
                                                                r.departmentId(),
                                                                Integer.MAX_VALUE))
                                        .thenComparingLong(
                                                HrAttendanceStore.RuleRecord::departmentId))
                        .toList();
        Integer companyDays =
                records.stream()
                        .filter(r -> r.departmentId() == 0)
                        .map(HrAttendanceStore.RuleRecord::expectedDays)
                        .findFirst()
                        .orElse(null);
        var rows =
                records.stream()
                        .filter(r -> departmentId == null || r.departmentId() == departmentId)
                        .filter(
                                r ->
                                        selectedStatus.isEmpty()
                                                || overview.lockedDepartmentIds()
                                                                .contains(r.departmentId())
                                                        == selectedStatus.equals("LOCKED"))
                        .map(
                                r ->
                                        new AttendanceRuleListRow(
                                                value,
                                                r.departmentId() == 0 ? null : r.departmentId(),
                                                r.departmentId() == 0
                                                        ? "公司默认"
                                                        : names.getOrDefault(
                                                                r.departmentId(),
                                                                "已移除部门（" + r.departmentId() + "）"),
                                                r.expectedDays(),
                                                r.revision(),
                                                overview.lockedDepartmentIds()
                                                        .contains(r.departmentId()),
                                                r.updatedTime(),
                                                store.ruleWritable(
                                                        tenant,
                                                        r.departmentId() == 0
                                                                ? null
                                                                : r.departmentId()), r.createdBy(), r.createdByName(), r.createdTime()))
                        .toList();
        return new AttendanceRuleListView(
                value,
                companyDays,
                records.stream().map(HrAttendanceStore.RuleRecord::departmentId).toList(),
                overview.lockedDepartmentIds().stream().sorted().toList(),
                new HrPageView<>(
                        rows.size(), begin, step, rows.stream().skip(begin).limit(step).toList()));
    }

    private static void appendDepartmentOrder(
            Long id, List<AttendanceDepartmentOption> departments, Map<Long, Integer> order) {
        if (order.containsKey(id)) return;
        order.put(id, order.size());
        departments.stream()
                .filter(d -> Objects.equals(d.parentId(), id))
                .forEach(d -> appendDepartmentOrder(d.id(), departments, order));
    }

    public AttendanceRuleView saveRule(AttendanceRuleCommand command) {
        var actor = actor(RULE_WRITE);
        validateRule(actor.tenantId().toString(), command);
        return store.saveRule(actor.tenantId().toString(), command, actor.principalId().toString());
    }

    public List<AttendanceRuleView> saveRules(List<AttendanceRuleCommand> commands) {
        var actor = actor(RULE_WRITE);
        if (commands == null || commands.isEmpty() || commands.size() > 500)
            throw bad("请一次选择1至500个部门");
        Set<Long> selected = new HashSet<>();
        String month = commands.getFirst() == null ? null : commands.getFirst().month();
        for (var command : commands) {
            validateRule(actor.tenantId().toString(), command);
            if (!Objects.equals(month, command.month())) throw bad("批量规则须为同一考勤月份");
            if (!selected.add(command.departmentId())) throw bad("批量规则不能重复选择部门");
        }
        if (selected.contains(null) && selected.size() > 1) throw bad("公司默认规则请单独设置");
        return store.saveRules(
                actor.tenantId().toString(), commands, actor.principalId().toString());
    }

    private void validateRule(String tenant, AttendanceRuleCommand command) {
        if (command == null) throw bad("请填写考勤规则");
        var month = month(command.month());
        revision(command.revision());
        store.requireRuleScope(tenant, command.departmentId(), RULE_WRITE);
        validateDepartment(tenant, command.departmentId());
        if ((command.expectedDays() == null && command.departmentId() == null)
                || (command.expectedDays() != null
                        && (command.expectedDays() < 0
                                || command.expectedDays() > month.lengthOfMonth())))
            throw bad("应出勤天数须在0至当月天数之间");
    }

    public List<AttendanceDepartmentOption> departments() {
        return departments(false);
    }

    public List<AttendanceDepartmentOption> departments(boolean forRules) {
        String action = forRules || !AuthorizationContext.hasPermission(READ) ? RULE_READ : READ;
        var actor = actor(action);
        return store.departments(actor.tenantId().toString(), action);
    }

    public AttendanceView list(
            String value,
            String employeeName,
            String employeeCode,
            Long departmentId,
            String attendanceStatus,
            int begin,
            int step) {
        var actor = actor(READ);
        var month = month(value);
        var filter =
                new HrAttendanceStore.EmployeeFilter(
                        filterText(employeeName, 128, "员工姓名"),
                        filterText(employeeCode, 50, "员工编号"),
                        departmentId,
                        attendanceStatus == null ? "" : attendanceStatus.trim());
        if (!filter.attendanceStatus().isEmpty()
                && !Set.of(PENDING, CONFIRMED).contains(filter.attendanceStatus()))
            throw bad("考勤状态无效");
        if (departmentId != null && departmentId <= 0) throw bad("部门无效");
        if (begin < 0 || step < 1 || step > 100) throw bad("分页范围无效");
        String tenant = actor.tenantId().toString();
        var page = store.employees(tenant, month, filter, begin, step);
        return attendanceView(tenant, value, month, page);
    }

    private AttendanceView attendanceView(
            String tenant,
            String value,
            YearMonth month,
            HrPageView<HrAttendanceStore.Employee> page) {
        var rule = store.rule(tenant, value, null);
        var departmentRules = new java.util.HashMap<>(store.departmentRules(tenant, value));
        var unavailableCalendars = new java.util.HashSet<Long>();
        if (payrollAttendance != null) {
            for (var department :
                    page.items().stream()
                            .filter(e -> e.confirmation() == null)
                            .map(HrAttendanceStore.Employee::departmentId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList()) {
                try {
                    payrollAttendance
                            .expectedDays(tenant, department, month)
                            .ifPresent(days -> departmentRules.put(department, days));
                } catch (BusinessException unavailable) {
                    unavailableCalendars.add(department);
                }
            }
        }
        var pending = page.items().stream().filter(e -> e.confirmation() == null).toList();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        LocalDate through = today.isBefore(month.atEndOfMonth()) ? today : month.atEndOfMonth();
        boolean future = through.isBefore(month.atDay(1));
        boolean provisional = !month.atEndOfMonth().isBefore(today);
        Map<String, Evidence> evidence = Map.of();
        String sourceError =
                unavailableCalendars.isEmpty() ? null : month.getYear() + "年法定日历尚不可用，相关部门暂不计算应出勤天数";
        if (!future && !pending.isEmpty()) {
            try {
                evidence =
                        source
                                .evidence(
                                        tenant,
                                        new Query(
                                                pending.stream()
                                                        .map(HrAttendanceStore.Employee::code)
                                                        .toList(),
                                                month.atDay(1),
                                                through))
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                Evidence::employeeCode, Function.identity()));
            } catch (RuntimeException unavailable) {
                // 数据源不可用时仍可查看和维护 HR 考勤；不能将取数失败折成零出勤。
                sourceError = (sourceError == null ? "" : sourceError + "；") + "销售打卡暂不可用，请稍后刷新";
            }
        }
        Map<String, com.rigour.hr.api.v1.model.AttendanceAssessmentView> assessments = Map.of();
        if (!future && payrollAttendance != null) {
            var assessmentThrough =
                    today.minusDays(1).isBefore(month.atEndOfMonth())
                            ? today.minusDays(1)
                            : month.atEndOfMonth();
            var batch =
                    payrollAttendance.applyDetailed(
                            tenant, month, through, assessmentThrough, pending, evidence);
            evidence = batch.evidence();
            assessments = batch.assessments();
        }
        var facts = evidence;
        var processAssessments = assessments;
        var stoppedDepartments =
                payrollAttendance == null
                        ? Set.<Long>of()
                        : payrollAttendance.stoppedDepartments(tenant, month);
        var rows =
                page.items().stream()
                        .map(
                                employee -> {
                                    if (employee.confirmation() != null)
                                        return employee.confirmation();
                                    var fact = facts.get(employee.code());
                                    String status =
                                            future
                                                    ? "FUTURE"
                                                    : fact == null
                                                            ? "UNAVAILABLE"
                                                            : !fact.linked()
                                                                    ? "UNLINKED"
                                                                    : "AVAILABLE";
                                    List<LocalDate> dates =
                                            "AVAILABLE".equals(status)
                                                    ? fact.dates().stream()
                                                            .filter(
                                                                    d ->
                                                                            !d.isBefore(
                                                                                            month
                                                                                                    .atDay(
                                                                                                            1))
                                                                                    && !d.isAfter(
                                                                                            through)
                                                                                    && (employee
                                                                                                            .entry()
                                                                                                    == null
                                                                                            || !d
                                                                                                    .isBefore(
                                                                                                            employee
                                                                                                                    .entry()))
                                                                                    && (employee
                                                                                                            .leave()
                                                                                                    == null
                                                                                            || !d
                                                                                                    .isAfter(
                                                                                                            employee
                                                                                                                    .leave())))
                                                            .distinct()
                                                            .sorted()
                                                            .toList()
                                                    : List.of();
                                    Integer punchDays =
                                            "AVAILABLE".equals(status) ? dates.size() : null;
                                    var record = employee.record();
                                    BigDecimal actual =
                                            record.actualDaysOverride() != null
                                                    ? record.actualDaysOverride()
                                                    : punchDays == null
                                                            ? null
                                                            : BigDecimal.valueOf(punchDays);
                                    // 只匹配员工当前所属部门，不沿组织树继承上级规则。
                                    Integer ownDays =
                                            employee.departmentId() == null
                                                    ? null
                                                    : departmentRules.get(employee.departmentId());
                                    boolean stopped =
                                            employee.departmentId() != null
                                                    && stoppedDepartments.contains(
                                                            employee.departmentId());
                                    var effectiveRule =
                                            HrAttendanceStore.EffectiveRule.resolve(
                                                    rule.expectedDays(),
                                                    ownDays,
                                                    stopped,
                                                    unavailableCalendars.contains(
                                                            employee.departmentId()));
                                    Integer expected = effectiveRule.expectedDays();
                                    String ruleSource = effectiveRule.source();
                                    BigDecimal absent =
                                            record.absentDaysOverride() != null
                                                    ? record.absentDaysOverride()
                                                    : provisional
                                                                    || actual == null
                                                                    || expected == null
                                                            ? null
                                                            : BigDecimal.valueOf(expected)
                                                                    .subtract(actual)
                                                                    .max(BigDecimal.ZERO);
                                    return new AttendanceRow(
                                            employee.code(),
                                            employee.name(),
                                            employee.departmentName(),
                                            expected,
                                            ruleSource,
                                            actual,
                                            absent,
                                            punchDays,
                                            record.actualDaysOverride(),
                                            record.absentDaysOverride(),
                                            record.leaveTypeCode(),
                                            PENDING,
                                            status,
                                            dates,
                                            record.reason(),
                                            record.revision(),
                                            record.exceptions(),
                                            processAssessments.get(employee.code()),
                                            record.assessmentOverride(),
                                            employee.entry(),
                                            employee.leave());
                                })
                        .toList();
        return new AttendanceView(
                value,
                future ? null : through,
                provisional,
                rule,
                sourceError,
                new HrPageView<>(page.total(), page.begin(), page.step(), rows));
    }

    public AttendanceRow previewRecord(
            String value, String employee, AttendanceRecordCommand command) {
        var actor = actor(READ);
        var period = month(value);
        if (employee == null || employee.isBlank() || employee.length() > 50 || command == null)
            throw bad("员工或考勤资料无效");
        validateDays(command.actualDaysOverride(), period, "实际出勤");
        validateDays(command.absentDaysOverride(), period, "缺勤");
        validateExceptions(command, period);
        validateAssessmentOverride(command.assessmentOverride());
        String tenant = actor.tenantId().toString();
        var current = store.employee(tenant, period, employee);
        if (current.confirmation() != null) return current.confirmation();
        var draft =
                new HrAttendanceStore.Employee(
                        current.code(),
                        current.name(),
                        current.departmentId(),
                        current.departmentName(),
                        current.entry(),
                        current.leave(),
                        command,
                        null);
        return attendanceView(tenant, value, period, new HrPageView<>(1, 0, 1, List.of(draft)))
                .page()
                .items()
                .getFirst();
    }

    @org.springframework.transaction.annotation.Transactional
    public void confirmRecord(String month, String employee, AttendanceRecordCommand command) {
        var actor = actor(READ);
        var period = month(month);
        if (employee == null || employee.isBlank() || employee.length() > 50 || command == null)
            throw bad("员工或考勤资料无效");
        revision(command.revision());
        validateDays(command.actualDaysOverride(), period, "实际出勤");
        validateDays(command.absentDaysOverride(), period, "缺勤");
        String reason = command.reason() == null ? "" : command.reason().trim();
        if (reason.length() > 500) throw bad("考勤核定说明最多500字");
        String type = command.leaveTypeCode();
        if (type == null || type.isBlank() || type.length() > 64) throw bad("请选择请假类型");
        validateExceptions(command, period);
        validateAssessmentOverride(command.assessmentOverride());
        String tenant = actor.tenantId().toString();
        if (ruleReviews != null) ruleReviews.lock(tenant);
        var employeeData = store.employee(tenant, period, employee);
        if (payrollAttendance != null
                && payrollAttendance.stopped(tenant, employeeData.departmentId(), period))
            throw bad("本月考勤规则已停用，请重新启用或配置替代规则后再确认");
        if (employeeData.confirmation() != null)
            throw new BusinessException(ErrorCode.CONFLICT, "考勤已确认，不能重复确认或修改，请刷新列表", List.of());
        if (!Objects.equals(employeeData.record().revision(), command.revision()))
            throw new BusinessException(ErrorCode.CONFLICT, "考勤资料已变更，请刷新后重试", List.of());
        if (!source.leaveTypes(actor.tenantId().toString()).contains(type))
            throw bad("请假类型已停用或不存在，请刷新后选择");
        if (!"NONE".equals(type) && reason.isBlank() && command.exceptions().isEmpty())
            throw bad("请填写考勤核定说明，或登记具体请假时段及说明");
        var normalized =
                new AttendanceRecordCommand(
                        command.actualDaysOverride(),
                        command.absentDaysOverride(),
                        type,
                        reason,
                        command.revision(),
                        command.exceptions(),
                        command.assessmentOverride());
        var adjusted =
                new HrAttendanceStore.Employee(
                        employeeData.code(),
                        employeeData.name(),
                        employeeData.departmentId(),
                        employeeData.departmentName(),
                        employeeData.entry(),
                        employeeData.leave(),
                        normalized,
                        null);
        var row =
                attendanceView(tenant, month, period, new HrPageView<>(1, 0, 1, List.of(adjusted)))
                        .page()
                        .items()
                        .getFirst();
        if (row.expectedDays() == null) throw bad("请先设置本部门或公司默认的当月考勤规则，再确认考勤");
        if (row.actualDays() == null || row.absentDays() == null)
            throw bad("请填写实际出勤和缺勤天数，或在系统统计完整后再确认");
        var snapshot =
                new AttendanceRow(
                        row.employeeCode(),
                        row.employeeName(),
                        row.departmentName(),
                        row.expectedDays(),
                        row.ruleSource(),
                        row.actualDays(),
                        row.absentDays(),
                        row.punchDays(),
                        row.actualDaysOverride(),
                        row.absentDaysOverride(),
                        row.leaveTypeCode(),
                        CONFIRMED,
                        row.sourceStatus(),
                        row.dates(),
                        row.reason(),
                        command.revision() + 1,
                        command.exceptions(),
                        row.assessment(),
                        command.assessmentOverride(),
                        row.entryDate(),
                        row.leaveDate());
        store.confirmRecord(
                tenant,
                month,
                employee,
                normalized,
                snapshot,
                employeeData.departmentId(),
                actor.principalId().toString());
        if (ruleReviews != null)
            ruleReviews.resolveSubject(
                    tenant,
                    "ATTENDANCE",
                    month + ":" + employee,
                    "按最新规则重新核定并确认考勤",
                    actor.principalId().toString());
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info(
                        "Attendance confirmed tenant={} employee={} month={} revision={}"
                                + " exceptions={} actor={}",
                        tenant,
                        employee,
                        month,
                        snapshot.revision(),
                        command.exceptions().size(),
                        actor.principalId());
    }

    @org.springframework.transaction.annotation.Transactional
    public void reopenRecord(String value, String employee, AttendanceReopenCommand command) {
        var a = actor(READ);
        var month = month(value);
        if (employee == null || employee.isBlank() || employee.length() > 50 || command == null)
            throw bad("员工或考勤资料无效");
        revision(command.revision());
        if (command.reason() == null
                || command.reason().isBlank()
                || command.reason().length() > 500) throw bad("请填写500字以内的撤销确认原因");
        String tenant = a.tenantId().toString();
        if (ruleReviews != null) ruleReviews.lock(tenant);
        var current = store.employee(tenant, month, employee);
        if (current.confirmation() == null) throw bad("考勤尚未确认，请刷新");
        store.reopenRecord(tenant, value, employee, command, a.principalId().toString());
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info(
                        "Attendance reopened tenant={} employee={} month={} actor={} revision={}",
                        tenant,
                        employee,
                        value,
                        a.principalId(),
                        command.revision());
    }

    private static void validateExceptions(AttendanceRecordCommand command, YearMonth month) {
        if (command.exceptions().isEmpty()) return;
        if (command.exceptions().size() > 100) throw bad("单月异常区间最多100条");
        if (command.actualDaysOverride() == null || command.absentDaysOverride() == null)
            throw bad("登记异常日期后，请同时核定实际出勤和缺勤天数");
        var sorted = new ArrayList<>(command.exceptions());
        for (var period : sorted) {
            if (period == null
                    || period.start() == null
                    || period.end() == null
                    || !period.start().isBefore(period.end())
                    || !period.start().isBefore(month.plusMonths(1).atDay(1).atStartOfDay())
                    || !period.end().isAfter(month.atDay(1).atStartOfDay()))
                throw bad("异常起止时间无效，须与核算月份有交集，结束时间不包含在区间内");
            if (!Set.of(
                            "COMPENSATORY_LEAVE",
                            "PAID_LEAVE",
                            "UNPAID_LEAVE",
                            "ACTIVITY",
                            "MISSING_PUNCH")
                    .contains(period.kind() == null ? "" : period.kind()))
                throw bad("请选择有效的考勤异常类型");
            if (period.reason() == null
                    || period.reason().isBlank()
                    || period.reason().length() > 500) throw bad("每条异常须填写500字以内的说明");
        }
        sorted.sort(Comparator.comparing(AttendanceExceptionPeriod::start));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).start().isBefore(sorted.get(i - 1).end()))
                throw bad("考勤异常时间不能重叠，请合并或调整区间");
            // End is exclusive: midnight belongs to the following day, not this record.
            if (!sorted.get(i)
                    .start()
                    .toLocalDate()
                    .isAfter(sorted.get(i - 1).end().minusNanos(1).toLocalDate()))
                throw bad("考勤异常日期不能重复，请合并或调整记录");
        }
    }

    private static void validateAssessmentOverride(
            com.rigour.hr.api.v1.model.AttendanceAssessmentOverride adjustment) {
        if (adjustment == null) return;
        if (adjustment.missingCheckins() == null && adjustment.missingVisits() == null)
            throw bad("请填写核定缺卡或缺拜访次数");
        for (var count : new Integer[] {adjustment.missingCheckins(), adjustment.missingVisits()})
            if (count != null && (count < 0 || count > 100000)) throw bad("核定缺失次数须为0至100000之间的整数");
        if (adjustment.reason() == null
                || adjustment.reason().isBlank()
                || adjustment.reason().length() > 500) throw bad("手工核定次数时，请填写500字以内的核定说明");
    }

    private static void validateDays(BigDecimal days, YearMonth month, String label) {
        if (days != null
                && (days.signum() < 0
                        || days.compareTo(BigDecimal.valueOf(month.lengthOfMonth())) > 0
                        || days.remainder(new BigDecimal("0.5")).signum() != 0))
            throw bad(label + "天数须在0至当月天数之间，且为0.5天的倍数");
    }

    private static CallerIdentity actor(String action) {
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null
                || !"TENANT".equals(actor.principalScope())
                || actor.userId() == null) throw new AuthorizationDeniedException(action);
        AuthorizationContext.requirePermission(action);
        return actor;
    }

    private void validateDepartment(String tenant, Long departmentId) {
        if (departmentId == null) return;
        if (departmentId <= 0
                || store.departments(tenant).stream().noneMatch(d -> departmentId.equals(d.id())))
            throw bad("部门不存在，请刷新后选择本公司的部门");
    }

    private static YearMonth month(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}"))
                throw new IllegalArgumentException();
            var month = YearMonth.parse(value);
            if (month.getYear() < 2000 || month.getYear() > 2100)
                throw new IllegalArgumentException();
            return month;
        } catch (RuntimeException invalid) {
            throw bad("月份格式应为YYYY-MM，年份在2000至2100之间");
        }
    }

    private static void revision(Integer value) {
        if (value == null || value < 0) throw bad("缺少数据版本，请刷新");
    }

    private static String filterText(String value, int max, String label) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max) throw bad(label + "搜索内容过长");
        return text;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
