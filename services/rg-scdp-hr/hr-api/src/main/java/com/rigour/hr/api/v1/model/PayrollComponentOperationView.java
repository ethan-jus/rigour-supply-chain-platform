package com.rigour.hr.api.v1.model;

public record PayrollComponentOperationView(
        String action, String reason, String actor, java.time.Instant time) {}
