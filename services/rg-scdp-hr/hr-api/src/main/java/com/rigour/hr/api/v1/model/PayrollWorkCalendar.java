package com.rigour.hr.api.v1.model;

import java.time.LocalDate;
import java.util.List;

public record PayrollWorkCalendar(
        List<Integer> weekdays,
        List<LocalDate> workDays,
        List<LocalDate> restDays,
        List<LocalDate> exemptDays,
        PayrollWorkWindow window,
        List<Integer> alternateWeekdays,
        LocalDate alternateAnchor,
        String source,
        String referenceVersion) {
    public PayrollWorkCalendar(
            List<Integer> weekdays,
            List<LocalDate> workDays,
            List<LocalDate> restDays,
            List<LocalDate> exemptDays,
            PayrollWorkWindow window,
            List<Integer> alternateWeekdays,
            LocalDate alternateAnchor) {
        this(
                weekdays,
                workDays,
                restDays,
                exemptDays,
                window,
                alternateWeekdays,
                alternateAnchor,
                null,
                null);
    }
}
