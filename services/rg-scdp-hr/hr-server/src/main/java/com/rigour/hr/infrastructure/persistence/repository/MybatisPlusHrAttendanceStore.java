package com.rigour.hr.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.hr.api.v1.model.HrAttendanceModels.*;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.application.port.out.HrAttendanceStore;
import com.rigour.hr.application.service.HrAttendanceService;
import com.rigour.hr.infrastructure.persistence.entity.*;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class MybatisPlusHrAttendanceStore implements HrAttendanceStore {
    private final HrAttendanceRuleMapper rules;
    private final HrAttendanceNoteMapper notes;
    private final HrAttendanceAuditMapper audits;
    private final HrAttendanceMonthLockMapper locks;
    private final HrAttendanceEmployeeMapper employees;
    private final HrDataScope scope;
    private final ObjectMapper json;

    public MybatisPlusHrAttendanceStore(
            HrAttendanceRuleMapper rules,
            HrAttendanceNoteMapper notes,
            HrAttendanceAuditMapper audits,
            HrAttendanceMonthLockMapper locks,
            HrAttendanceEmployeeMapper employees,
            HrDataScope scope,
            ObjectMapper json) {
        this.rules = rules;
        this.notes = notes;
        this.audits = audits;
        this.locks = locks;
        this.employees = employees;
        this.scope = scope;
        this.json = json;
    }

    private static <T> QueryWrapper<T> monthQuery(String tenant, String month) {
        return new QueryWrapper<T>().eq("tenant_id", tenant).eq("attendance_month", month);
    }

    public List<DepartmentOption> departments(String tenant) {
        return employees.departments(tenant);
    }

    public RuleOverview ruleOverview(String tenant, String month) {
        var records =
                rules
                        .selectList(
                                MybatisPlusHrAttendanceStore.<HrAttendanceRuleEntity>monthQuery(
                                                tenant, month)
                                        .isNotNull("expected_days"))
                        .stream()
                        .map(
                                r ->
                                        new RuleRecord(
                                                r.departmentId,
                                                r.expectedDays,
                                                r.revision,
                                                r.updatedTime.toInstant(ZoneOffset.UTC)))
                        .toList();
        var locked = new HashSet<Long>();
        notes.selectList(
                        MybatisPlusHrAttendanceStore.<HrAttendanceNoteEntity>monthQuery(
                                        tenant, month)
                                .select("confirmed_department_id", "confirmed_rule_department_id")
                                .eq("attendance_status", HrAttendanceService.CONFIRMED))
                .forEach(
                        n -> {
                            if (n.confirmedDepartmentId != null)
                                locked.add(n.confirmedDepartmentId);
                            if (Objects.equals(n.confirmedRuleDepartmentId, 0L)) locked.add(0L);
                        });
        return new RuleOverview(records, locked);
    }

    public Rule rule(String tenant, String month, Long departmentId) {
        long id = departmentId == null ? 0 : departmentId;
        var rows =
                rules.selectList(
                        MybatisPlusHrAttendanceStore.<HrAttendanceRuleEntity>monthQuery(
                                        tenant, month)
                                .in("department_id", List.of(0L, id)));
        Integer company =
                rows.stream()
                        .filter(r -> r.departmentId == 0)
                        .map(r -> r.expectedDays)
                        .findFirst()
                        .orElse(null);
        var own = rows.stream().filter(r -> r.departmentId == id).findFirst().orElse(null);
        return new Rule(
                month,
                departmentId,
                own == null ? null : own.expectedDays,
                own == null ? 0 : own.revision,
                company,
                ruleLocked(tenant, month, departmentId));
    }

    public Map<Long, Integer> departmentRules(String tenant, String month) {
        return rules
                .selectList(
                        MybatisPlusHrAttendanceStore.<HrAttendanceRuleEntity>monthQuery(
                                        tenant, month)
                                .gt("department_id", 0)
                                .isNotNull("expected_days"))
                .stream()
                .collect(Collectors.toMap(r -> r.departmentId, r -> r.expectedDays));
    }

    @Transactional
    public Rule saveRule(String tenant, RuleCommand command, String actor) {
        lockMonth(tenant, command.month());
        if (ruleLocked(tenant, command.month(), command.departmentId()))
            throw new BusinessException(
                    ErrorCode.CONFLICT, "当月关联员工已有考勤确认，对应规则已锁定，不能修改或重新设置", List.of());
        var current = rule(tenant, command.month(), command.departmentId());
        if (current.revision() != command.revision()) throw conflict();
        if (current.revision() > 0
                && Objects.equals(current.expectedDays(), command.expectedDays()))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "规则未发生变化，无需重复设置", List.of());
        long id = command.departmentId() == null ? 0 : command.departmentId();
        try {
            if (command.revision() == 0) {
                var row = new HrAttendanceRuleEntity();
                row.tenantId = tenant;
                row.attendanceMonth = command.month();
                row.departmentId = id;
                row.expectedDays = command.expectedDays();
                row.revision = 1;
                row.updatedBy = actor;
                row.updatedTime = LocalDateTime.now(ZoneOffset.UTC);
                rules.insert(row);
            } else if (rules.update(
                            null,
                            new UpdateWrapper<HrAttendanceRuleEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("attendance_month", command.month())
                                    .eq("department_id", id)
                                    .eq("revision", command.revision())
                                    .set("expected_days", command.expectedDays())
                                    .setSql("revision=revision+1")
                                    .set("updated_by", actor)
                                    .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)))
                    != 1) throw conflict();
        } catch (DuplicateKeyException duplicate) {
            throw conflict();
        }
        var audit = new HrAttendanceAuditEntity();
        audit.tenantId = tenant;
        audit.attendanceMonth = command.month();
        audit.departmentId = command.departmentId();
        audit.actionCode = "RULE";
        audit.expectedDays = command.expectedDays();
        audit.revision = command.revision() + 1;
        audit.actorId = actor;
        audits.insert(audit);
        return rule(tenant, command.month(), command.departmentId());
    }

    public HrPageView<Employee> employees(
            String tenant, YearMonth month, EmployeeFilter filter, int begin, int step) {
        return employeePage(tenant, month, filter, begin, step, HrAttendanceService.READ, false);
    }

    public Employee employee(String tenant, YearMonth month, String code) {
        return employeePage(
                        tenant,
                        month,
                        new EmployeeFilter("", code, null, ""),
                        0,
                        1,
                        HrAttendanceService.WRITE,
                        true)
                .items()
                .stream()
                .findFirst()
                .orElseThrow(() -> new AuthorizationDeniedException(HrAttendanceService.WRITE));
    }

    private HrPageView<Employee> employeePage(
            String tenant,
            YearMonth month,
            EmployeeFilter filter,
            int begin,
            int step,
            String action,
            boolean exactCode) {
        var query =
                new QueryWrapper<HrEmployeeEntity>()
                        .eq("e.tenant_id", tenant)
                        .eq("e.deleted", 0)
                        .apply(
                                "(e.entry_date IS NULL OR DATE(e.entry_date)<={0})",
                                month.atEndOfMonth())
                        .apply("(e.leave_date IS NULL OR DATE(e.leave_date)>={0})", month.atDay(1));
        var predicate = scope.employeePredicate(action, "e.");
        StringBuilder sql = new StringBuilder();
        int i = 0;
        for (char c : predicate.sql().toCharArray()) {
            if (c == '?') sql.append('{').append(i++).append('}');
            else sql.append(c);
        }
        query.apply(sql.toString(), predicate.args().toArray());
        if (!filter.name().isEmpty()) query.apply("LOCATE({0},e.employee_name)>0", filter.name());
        if (!filter.code().isEmpty()) {
            if (exactCode) query.eq("e.employee_code", filter.code());
            else query.apply("LOCATE({0},e.employee_code)>0", filter.code());
        }
        if (filter.departmentId() != null)
            query.apply(
                    "e.department_id IN (SELECT descendant_id FROM hr_department_closure WHERE"
                            + " tenant_id={0} AND ancestor_id={1})",
                    tenant, filter.departmentId());
        if (!filter.attendanceStatus().isEmpty()) {
            // 参数比较避免存量员工表与考勤表不同排序规则的列 JOIN。
            var codes =
                    notes
                            .selectList(
                                    MybatisPlusHrAttendanceStore.<HrAttendanceNoteEntity>monthQuery(
                                                    tenant, month.toString())
                                            .select("employee_code")
                                            .eq("attendance_status", HrAttendanceService.CONFIRMED))
                            .stream()
                            .map(n -> n.employeeCode)
                            .toList();
            boolean confirmed = HrAttendanceService.CONFIRMED.equals(filter.attendanceStatus());
            if (codes.isEmpty()) {
                if (confirmed) query.apply("1=0");
            } else if (confirmed) query.in("e.employee_code", codes);
            else query.notIn("e.employee_code", codes);
        }
        long total = employees.countEmployees(query);
        var rows =
                employees.employees(
                        query.orderByAsc("d.department_name", "e.employee_name", "e.employee_code")
                                .last("LIMIT " + step + " OFFSET " + begin));
        Map<String, HrAttendanceNoteEntity> records =
                rows.isEmpty()
                        ? Map.of()
                        : notes
                                .selectList(
                                        MybatisPlusHrAttendanceStore
                                                .<HrAttendanceNoteEntity>monthQuery(
                                                        tenant, month.toString())
                                                .in(
                                                        "employee_code",
                                                        rows.stream()
                                                                .map(
                                                                        HrAttendanceEmployeeMapper
                                                                                        .EmployeeFact
                                                                                ::code)
                                                                .toList()))
                                .stream()
                                .collect(
                                        Collectors.toMap(n -> n.employeeCode, Function.identity()));
        var items =
                rows.stream()
                        .map(
                                e -> {
                                    var n = records.get(e.code());
                                    return new Employee(
                                            e.code(),
                                            e.name(),
                                            e.departmentId(),
                                            e.departmentName(),
                                            e.entryDate(),
                                            e.leaveDate(),
                                            n == null
                                                    ? new RecordCommand(null, null, "NONE", "", 0)
                                                    : new RecordCommand(
                                                            n.actualDaysOverride,
                                                            n.absentDaysOverride,
                                                            n.leaveTypeCode,
                                                            n.reason,
                                                            n.revision),
                                            n == null || n.confirmedSnapshotJson == null
                                                    ? null
                                                    : json.readValue(
                                                            n.confirmedSnapshotJson, Row.class));
                                })
                        .toList();
        return new HrPageView<>(total, begin, step, items);
    }

    @Transactional
    public void confirmRecord(
            String tenant,
            String month,
            String employee,
            RecordCommand command,
            Row snapshot,
            Long departmentId,
            String actor) {
        lockMonth(tenant, month);
        var currentEmployee = employee(tenant, YearMonth.parse(month), employee);
        if (!Objects.equals(currentEmployee.departmentId(), departmentId))
            throw new BusinessException(ErrorCode.CONFLICT, "员工部门已变更，请刷新后重新确认", List.of());
        var currentRule = rule(tenant, month, departmentId);
        Integer effective =
                currentRule.expectedDays() == null
                        ? currentRule.companyExpectedDays()
                        : currentRule.expectedDays();
        boolean ownRule = departmentId != null && currentRule.expectedDays() != null;
        String source = effective == null ? "UNSET" : ownRule ? "DEPARTMENT" : "COMPANY";
        if (!Objects.equals(effective, snapshot.expectedDays())
                || !source.equals(snapshot.ruleSource()))
            throw new BusinessException(ErrorCode.CONFLICT, "考勤规则已变更，请刷新后重新确认", List.of());
        long ruleDepartment = ownRule ? departmentId : 0;
        String frozen = json.writeValueAsString(snapshot);
        try {
            if (command.revision() == 0) {
                var row = new HrAttendanceNoteEntity();
                row.tenantId = tenant;
                row.attendanceMonth = month;
                row.employeeCode = employee;
                row.reason = command.reason();
                row.actualDaysOverride = command.actualDaysOverride();
                row.absentDaysOverride = command.absentDaysOverride();
                row.leaveTypeCode = command.leaveTypeCode();
                row.revision = 1;
                row.updatedBy = actor;
                row.updatedTime = LocalDateTime.now(ZoneOffset.UTC);
                row.attendanceStatus = HrAttendanceService.CONFIRMED;
                row.confirmedSnapshotJson = frozen;
                row.confirmedDepartmentId = departmentId;
                row.confirmedRuleDepartmentId = ruleDepartment;
                notes.insert(row);
            } else if (notes.update(
                            null,
                            new UpdateWrapper<HrAttendanceNoteEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("attendance_month", month)
                                    .eq("employee_code", employee)
                                    .eq("revision", command.revision())
                                    .eq("attendance_status", HrAttendanceService.PENDING)
                                    .set("reason", command.reason())
                                    .set("actual_days_override", command.actualDaysOverride())
                                    .set("absent_days_override", command.absentDaysOverride())
                                    .set("leave_type_code", command.leaveTypeCode())
                                    .setSql("revision=revision+1")
                                    .set("updated_by", actor)
                                    .set("updated_time", LocalDateTime.now(ZoneOffset.UTC))
                                    .set("attendance_status", HrAttendanceService.CONFIRMED)
                                    .set("confirmed_snapshot_json", frozen)
                                    .set("confirmed_department_id", departmentId)
                                    .set("confirmed_rule_department_id", ruleDepartment))
                    != 1) throw conflict();
        } catch (DuplicateKeyException duplicate) {
            throw conflict();
        }
        var audit = new HrAttendanceAuditEntity();
        audit.tenantId = tenant;
        audit.attendanceMonth = month;
        audit.employeeCode = employee;
        audit.actionCode = "CONFIRM";
        audit.reason = command.reason();
        audit.actualDaysOverride = command.actualDaysOverride();
        audit.absentDaysOverride = command.absentDaysOverride();
        audit.leaveTypeCode = command.leaveTypeCode();
        audit.revision = command.revision() + 1;
        audit.actorId = actor;
        audit.confirmedSnapshotJson = frozen;
        audits.insert(audit);
    }

    private void lockMonth(String tenant, String month) {
        locks.ensureMonth(tenant, month);
        locks.lockMonth(tenant, month);
    }

    private boolean ruleLocked(String tenant, String month, Long departmentId) {
        return notes.selectCount(
                        MybatisPlusHrAttendanceStore.<HrAttendanceNoteEntity>monthQuery(
                                        tenant, month)
                                .eq("attendance_status", HrAttendanceService.CONFIRMED)
                                .eq(
                                        departmentId == null
                                                ? "confirmed_rule_department_id"
                                                : "confirmed_department_id",
                                        departmentId == null ? 0 : departmentId))
                > 0;
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "考勤资料已变更，请刷新后重试", List.of());
    }
}
