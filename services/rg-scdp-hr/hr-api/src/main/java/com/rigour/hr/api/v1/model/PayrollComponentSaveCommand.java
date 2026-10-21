package com.rigour.hr.api.v1.model;

public record PayrollComponentSaveCommand(
        String requestId,
        String previousId,
        PayrollComponentDefinition definition,
        String reason) {}
