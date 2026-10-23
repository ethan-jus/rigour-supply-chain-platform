package com.rigour.hr.api.v1.model;

public record PayrollComponentStateChange(
        int revision,
        String effectiveMonth,
        boolean enabled,
        String reason,
        String actor,
        java.time.Instant time) {}
