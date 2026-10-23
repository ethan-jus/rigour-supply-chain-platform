package com.rigour.hr.api.v1.model;

public record AttendanceRuleView(
        String month,
        Long departmentId,
        Integer expectedDays,
        int revision,
        Integer companyExpectedDays,
        boolean locked,
        boolean canWrite) {
    public AttendanceRuleView(String month, Long departmentId, Integer expectedDays, int revision,
            Integer companyExpectedDays, boolean locked) {
        this(month, departmentId, expectedDays, revision, companyExpectedDays, locked, true);
    }
}
