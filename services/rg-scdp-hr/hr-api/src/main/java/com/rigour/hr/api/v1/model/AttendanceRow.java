package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AttendanceRow(
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
        int revision,
        List<AttendanceExceptionPeriod> exceptions) {
    public AttendanceRow {
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
    }

    public AttendanceRow(
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
            int revision) {
        this(
                employeeCode,
                employeeName,
                departmentName,
                expectedDays,
                ruleSource,
                actualDays,
                absentDays,
                punchDays,
                actualDaysOverride,
                absentDaysOverride,
                leaveTypeCode,
                attendanceStatus,
                sourceStatus,
                dates,
                reason,
                revision,
                List.of());
    }
}
