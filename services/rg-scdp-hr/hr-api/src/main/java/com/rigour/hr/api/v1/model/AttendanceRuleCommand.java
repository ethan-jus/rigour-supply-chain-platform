package com.rigour.hr.api.v1.model;

public record AttendanceRuleCommand(
        String month, Long departmentId, Integer expectedDays, Integer revision) {}
