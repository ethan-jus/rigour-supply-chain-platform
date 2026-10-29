package com.rigour.hr.api.v1.model;

import java.time.Instant;

public record AttendanceRuleListRow(
        String month,
        Long departmentId,
        String departmentName,
        int expectedDays,
        int revision,
        boolean locked,
        Instant updatedTime,
        boolean canWrite,
        String createdBy, String createdByName, Instant createdTime) {
    public AttendanceRuleListRow(String month, Long departmentId, String departmentName, int expectedDays, int revision, boolean locked, Instant updatedTime, boolean canWrite) {
        this(month, departmentId, departmentName, expectedDays, revision, locked, updatedTime, canWrite, null, null, null);
    }

    public AttendanceRuleListRow(String month, Long departmentId, String departmentName, int expectedDays,
            int revision, boolean locked, Instant updatedTime) {
        this(month, departmentId, departmentName, expectedDays, revision, locked, updatedTime, true);
    }
}
