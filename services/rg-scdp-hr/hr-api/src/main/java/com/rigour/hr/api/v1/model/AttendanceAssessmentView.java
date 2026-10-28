package com.rigour.hr.api.v1.model;

import java.time.LocalDate;
import java.util.List;

/** Process assessment is independent of attendance days. Unknown counts stay null. */
public record AttendanceAssessmentView(
        String status,
        LocalDate through,
        Integer missingCheckins,
        Integer missingVisits,
        List<PayrollPerformanceDayView> days,
        String message) {
    public AttendanceAssessmentView {
        days = days == null ? List.of() : List.copyOf(days);
    }
}
