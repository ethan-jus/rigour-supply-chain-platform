package com.rigour.hr.api.v1.model;

/** Monthly operator determination; null keeps the corresponding system count. */
public record AttendanceAssessmentOverride(
        Integer missingCheckins, Integer missingVisits, String reason) {}
