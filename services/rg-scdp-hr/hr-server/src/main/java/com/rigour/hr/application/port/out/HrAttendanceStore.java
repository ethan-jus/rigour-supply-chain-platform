package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.HrAttendanceModels.*;
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
            RecordCommand record,
            Row confirmation) {}

    record EmployeeFilter(String name, String code, Long departmentId, String attendanceStatus) {}

    record RuleRecord(long departmentId, int expectedDays, int revision, Instant updatedTime) {}

    record RuleOverview(List<RuleRecord> rules, Set<Long> lockedDepartmentIds) {}

    List<DepartmentOption> departments(String tenant);

    RuleOverview ruleOverview(String tenant, String month);

    Rule rule(String tenant, String month, Long departmentId);

    Map<Long, Integer> departmentRules(String tenant, String month);

    Rule saveRule(String tenant, RuleCommand command, String actor);

    HrPageView<Employee> employees(
            String tenant, YearMonth month, EmployeeFilter filter, int begin, int step);

    Employee employee(String tenant, YearMonth month, String employeeCode);

    void confirmRecord(
            String tenant,
            String month,
            String employee,
            RecordCommand command,
            Row snapshot,
            Long departmentId,
            String actor);
}
