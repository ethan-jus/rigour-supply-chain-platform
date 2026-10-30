package com.rigour.hr.api.v1.model;

public record AttendanceRuleView(
        String month,
        Long departmentId,
        Integer expectedDays,
        int revision,
        Integer companyExpectedDays,
        boolean locked,
        boolean canWrite,
        Integer calendarExpectedDays,
        String calendarMessage,
        String createdBy, String createdByName, java.time.Instant createdTime) {
    public AttendanceRuleView(String month, Long departmentId, Integer expectedDays, int revision, Integer companyExpectedDays, boolean locked, boolean canWrite, Integer calendarExpectedDays, String calendarMessage) {
        this(month, departmentId, expectedDays, revision, companyExpectedDays, locked, canWrite, calendarExpectedDays, calendarMessage, null, null, null);
    }

    public AttendanceRuleView(
            String month,
            Long departmentId,
            Integer expectedDays,
            int revision,
            Integer companyExpectedDays,
            boolean locked,
            boolean canWrite) {
        this(
                month,
                departmentId,
                expectedDays,
                revision,
                companyExpectedDays,
                locked,
                canWrite,
                null,
                null);
    }

    public AttendanceRuleView(String month, Long departmentId, Integer expectedDays, int revision,
            Integer companyExpectedDays, boolean locked) {
        this(month, departmentId, expectedDays, revision, companyExpectedDays, locked, true);
    }
}
