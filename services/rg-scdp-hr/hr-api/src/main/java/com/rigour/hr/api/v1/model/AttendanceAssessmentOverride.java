package com.rigour.hr.api.v1.model;

/** Monthly operator determination; null keeps the corresponding system count. */
public record AttendanceAssessmentOverride(
        Integer missingCheckins, Integer missingVisits, String reason,
        Integer actualCheckins, Integer actualVisits, Integer expectedCheckins, Integer expectedVisits) {
    public AttendanceAssessmentOverride(Integer missingCheckins, Integer missingVisits, String reason) {
        this(missingCheckins, missingVisits, reason, null, null, null, null);
    }
}
