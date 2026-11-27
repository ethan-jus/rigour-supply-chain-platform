package com.rigour.hr.domain.payroll;

import java.time.LocalDate;
import java.time.YearMonth;

public enum PayrollEmployment {
    REGULAR,
    NOT_STARTED,
    LEFT_BEFORE_MONTH,
    MISSING_ENTRY_DATE,
    MISSING_LEAVE_DATE,
    INVALID_DATES;

    public static PayrollEmployment forMonth(
            LocalDate entryDate, LocalDate leaveDate, String employmentStatus, YearMonth month) {
        if (entryDate != null && entryDate.isAfter(month.atEndOfMonth())) return NOT_STARTED;
        if (entryDate == null) return MISSING_ENTRY_DATE;
        if (leaveDate == null && "LEFT".equals(employmentStatus)) return MISSING_LEAVE_DATE;
        if (leaveDate != null && leaveDate.isBefore(entryDate)) return INVALID_DATES;
        if (leaveDate != null && leaveDate.isBefore(month.atDay(1))) return LEFT_BEFORE_MONTH;
        return REGULAR;
    }
}
