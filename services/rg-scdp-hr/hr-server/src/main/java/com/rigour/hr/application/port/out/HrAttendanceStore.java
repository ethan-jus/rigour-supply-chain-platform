package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.AttendanceDepartmentOption;
import com.rigour.hr.api.v1.model.AttendanceRecordCommand;
import com.rigour.hr.api.v1.model.AttendanceReopenCommand;
import com.rigour.hr.api.v1.model.AttendanceRow;
import com.rigour.hr.api.v1.model.AttendanceRuleCommand;
import com.rigour.hr.api.v1.model.AttendanceRuleView;
import com.rigour.hr.api.v1.model.HrPageView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface HrAttendanceStore {
    record Employee(
            String code,
            String name,
            Long departmentId,
            String departmentName,
            LocalDate entry,
            LocalDate leave,
            AttendanceRecordCommand record,
            AttendanceRow confirmation) {}

    record EmployeeFilter(String name, String code, Long departmentId, String attendanceStatus) {}

    record RuleRecord(long departmentId, int expectedDays, int revision, Instant updatedTime) {}

    record RuleOverview(List<RuleRecord> rules, Set<Long> lockedDepartmentIds) {}

    /** Display and persistence must use the same precedence for the effective calendar. */
    record EffectiveRule(Integer expectedDays, String source) {
        public static EffectiveRule resolve(
                Integer companyDays, Integer departmentDays, boolean stopped, boolean unavailable) {
            Integer days =
                    stopped || unavailable
                            ? null
                            : departmentDays == null ? companyDays : departmentDays;
            return new EffectiveRule(
                    days,
                    stopped
                            ? "DISABLED"
                            : days == null
                                    ? "UNSET"
                                    : departmentDays == null ? "COMPANY" : "DEPARTMENT");
        }
    }

    List<AttendanceDepartmentOption> departments(String tenant);

    List<AttendanceDepartmentOption> departments(String tenant, String action);

    void requireRuleScope(String tenant, Long departmentId, String action);

    boolean ruleWritable(String tenant, Long departmentId);

    RuleOverview ruleOverview(String tenant, String month);

    AttendanceRuleView rule(String tenant, String month, Long departmentId);

    Map<Long, Integer> departmentRules(String tenant, String month);

    AttendanceRuleView saveRule(String tenant, AttendanceRuleCommand command, String actor);

    List<AttendanceRuleView> saveRules(
            String tenant, List<AttendanceRuleCommand> commands, String actor);

    HrPageView<Employee> employees(
            String tenant, YearMonth month, EmployeeFilter filter, int begin, int step);

    Employee employee(String tenant, YearMonth month, String employeeCode);

    Employee employee(String tenant, YearMonth month, String employeeCode, String action);

    boolean reopenedSince(String tenant, YearMonth month, String employeeCode, Instant since);

    void reopenRecord(
            String tenant,
            String month,
            String employee,
            AttendanceReopenCommand command,
            String actor);

    void confirmRecord(
            String tenant,
            String month,
            String employee,
            AttendanceRecordCommand command,
            AttendanceRow snapshot,
            Long departmentId,
            String actor);
}
