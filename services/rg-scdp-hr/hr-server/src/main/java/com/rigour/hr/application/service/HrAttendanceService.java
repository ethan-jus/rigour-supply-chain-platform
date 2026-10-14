package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.HrAttendanceModels.*;
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

    public HrAttendanceService(HrAttendanceStore store, HrAttendanceSource source, Clock clock) {
        this.store = store;
        this.source = source;
        this.clock = clock;
    }

    public Rule rule(String month, Long departmentId) {
        var actor = actor(RULE_READ);
        month(month);
        String tenant = actor.tenantId().toString();
        validateDepartment(tenant, departmentId);
        return store.rule(tenant, month, departmentId);
    }

    public RuleListView rules(String value, Long departmentId, String status, int begin, int step) {
        var actor = actor(RULE_READ);
        month(value);
        if (departmentId != null && departmentId < 0) throw bad("部门无效");
        String selectedStatus = status == null ? "" : status.trim();
        if (!Set.of("", "LOCKED", "UNLOCKED").contains(selectedStatus)) throw bad("规则状态无效");
        if (begin < 0 || step < 1 || step > 100) throw bad("分页范围无效");
        String tenant = actor.tenantId().toString();
        var overview = store.ruleOverview(tenant, value);
        var departments =
                store.departments(tenant).stream()
                        .sorted(
                                Comparator.comparingInt(DepartmentOption::sortOrder)
                                        .thenComparing(DepartmentOption::id))
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
                                        new RuleListRow(
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
                                                r.updatedTime()))
                        .toList();
        return new RuleListView(
                value,
                companyDays,
                records.stream().map(HrAttendanceStore.RuleRecord::departmentId).toList(),
                overview.lockedDepartmentIds().stream().sorted().toList(),
                new HrPageView<>(
                        rows.size(), begin, step, rows.stream().skip(begin).limit(step).toList()));
    }

    private static void appendDepartmentOrder(
            Long id, List<DepartmentOption> departments, Map<Long, Integer> order) {
        if (order.containsKey(id)) return;
        order.put(id, order.size());
        departments.stream()
                .filter(d -> Objects.equals(d.parentId(), id))
                .forEach(d -> appendDepartmentOrder(d.id(), departments, order));
    }

    public Rule saveRule(RuleCommand command) {
        var actor = actor(RULE_WRITE);
        if (command == null) throw bad("请填写考勤规则");
        var month = month(command.month());
        revision(command.revision());
        validateDepartment(actor.tenantId().toString(), command.departmentId());
        if ((command.expectedDays() == null && command.departmentId() == null)
                || (command.expectedDays() != null
                        && (command.expectedDays() < 0
                                || command.expectedDays() > month.lengthOfMonth())))
            throw bad("应出勤天数须在0至当月天数之间");
        return store.saveRule(actor.tenantId().toString(), command, actor.principalId().toString());
    }

    public List<DepartmentOption> departments() {
        var actor = actor(AuthorizationContext.hasPermission(READ) ? READ : RULE_READ);
        return store.departments(actor.tenantId().toString());
    }

    public View list(
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

    private View attendanceView(
            String tenant,
            String value,
            YearMonth month,
            HrPageView<HrAttendanceStore.Employee> page) {
        var rule = store.rule(tenant, value, null);
        var departmentRules = store.departmentRules(tenant, value);
        var pending = page.items().stream().filter(e -> e.confirmation() == null).toList();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        LocalDate through = today.isBefore(month.atEndOfMonth()) ? today : month.atEndOfMonth();
        boolean future = through.isBefore(month.atDay(1));
        boolean provisional = !month.atEndOfMonth().isBefore(today);
        Map<String, Evidence> evidence = Map.of();
        String sourceError = null;
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
                sourceError = "销售打卡暂不可用，请稍后刷新";
            }
        }
        var facts = evidence;
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
                                    Integer expected =
                                            ownDays == null ? rule.expectedDays() : ownDays;
                                    String ruleSource =
                                            expected == null
                                                    ? "UNSET"
                                                    : ownDays == null ? "COMPANY" : "DEPARTMENT";
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
                                    return new Row(
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
                                            record.revision());
                                })
                        .toList();
        return new View(
                value,
                future ? null : through,
                provisional,
                rule,
                sourceError,
                new HrPageView<>(page.total(), page.begin(), page.step(), rows));
    }

    public void confirmRecord(String month, String employee, RecordCommand command) {
        var actor = actor(WRITE);
        var period = month(month);
        if (employee == null || employee.isBlank() || employee.length() > 50 || command == null)
            throw bad("员工或考勤资料无效");
        revision(command.revision());
        validateDays(command.actualDaysOverride(), period, "实际出勤");
        validateDays(command.absentDaysOverride(), period, "缺勤");
        String reason = command.reason() == null ? "" : command.reason().trim();
        if (reason.length() > 500) throw bad("缺卡原因最多500字");
        String type = command.leaveTypeCode();
        if (type == null || type.isBlank() || type.length() > 64) throw bad("请选择请假类型");
        String tenant = actor.tenantId().toString();
        var employeeData = store.employee(tenant, period, employee);
        if (employeeData.confirmation() != null)
            throw new BusinessException(ErrorCode.CONFLICT, "考勤已确认，不能重复确认或修改，请刷新列表", List.of());
        if (!Objects.equals(employeeData.record().revision(), command.revision()))
            throw new BusinessException(ErrorCode.CONFLICT, "考勤资料已变更，请刷新后重试", List.of());
        if (!source.leaveTypes(actor.tenantId().toString()).contains(type))
            throw bad("请假类型已停用或不存在，请刷新后选择");
        var normalized =
                new RecordCommand(
                        command.actualDaysOverride(),
                        command.absentDaysOverride(),
                        type,
                        reason,
                        command.revision());
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
                new Row(
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
                        command.revision() + 1);
        store.confirmRecord(
                tenant,
                month,
                employee,
                normalized,
                snapshot,
                employeeData.departmentId(),
                actor.principalId().toString());
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
