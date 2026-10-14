package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class HrAttendanceModels {
    private HrAttendanceModels() {}

    public record Rule(
            String month,
            Long departmentId,
            Integer expectedDays,
            int revision,
            Integer companyExpectedDays,
            boolean locked) {}

    public record RuleListRow(
            String month,
            Long departmentId,
            String departmentName,
            int expectedDays,
            int revision,
            boolean locked,
            Instant updatedTime) {}

    public record RuleListView(
            String month,
            Integer companyExpectedDays,
            List<Long> configuredDepartmentIds,
            List<Long> lockedDepartmentIds,
            HrPageView<RuleListRow> page) {}

    public record RuleCommand(
            String month, Long departmentId, Integer expectedDays, Integer revision) {}

    public record RecordCommand(
            BigDecimal actualDaysOverride,
            BigDecimal absentDaysOverride,
            String leaveTypeCode,
            String reason,
            Integer revision) {}

    public record DepartmentOption(Long id, Long parentId, String departmentName, int sortOrder) {}

    public record Row(
            String employeeCode,
            String employeeName,
            String departmentName,
            Integer expectedDays,
            String ruleSource,
            BigDecimal actualDays,
            BigDecimal absentDays,
            Integer punchDays,
            BigDecimal actualDaysOverride,
            BigDecimal absentDaysOverride,
            String leaveTypeCode,
            String attendanceStatus,
            String sourceStatus,
            List<LocalDate> dates,
            String reason,
            int revision) {}

    public record View(
            String month,
            LocalDate through,
            boolean provisional,
            Rule rule,
            String sourceError,
            HrPageView<Row> page) {}
}
