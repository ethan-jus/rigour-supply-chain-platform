package com.rigour.hr.api.v1.model;

public record PayrollSchemeSaveCommand(
        String requestId,
        Integer revision,
        String schemeId,
        PayrollSchemeDefinition definition,
        String reason) {}
