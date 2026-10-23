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
        boolean canWrite) {
    public AttendanceRuleListRow(String month, Long departmentId, String departmentName, int expectedDays,
            int revision, boolean locked, Instant updatedTime) {
        this(month, departmentId, departmentName, expectedDays, revision, locked, updatedTime, true);
    }
}
