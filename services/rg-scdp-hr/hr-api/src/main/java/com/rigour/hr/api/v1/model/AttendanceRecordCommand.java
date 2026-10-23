package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record AttendanceRecordCommand(
        BigDecimal actualDaysOverride,
        BigDecimal absentDaysOverride,
        String leaveTypeCode,
        String reason,
        Integer revision,
        List<AttendanceExceptionPeriod> exceptions) {
    public AttendanceRecordCommand {
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
    }

    public AttendanceRecordCommand(
            BigDecimal actualDaysOverride,
            BigDecimal absentDaysOverride,
            String leaveTypeCode,
            String reason,
            Integer revision) {
        this(actualDaysOverride, absentDaysOverride, leaveTypeCode, reason, revision, List.of());
    }
}
