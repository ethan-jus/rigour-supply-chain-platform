package com.rigour.hr.api.v1.model;

import java.time.Instant;

public record PayrollComponentVersionView(
        String id,
        String ruleId,
        int version,
        PayrollComponentDefinition definition,
        String reason,
        String createdBy,
        Instant createdTime,
        String ruleCode) {
    public PayrollComponentVersionView(
            String id,
            String ruleId,
            int version,
            PayrollComponentDefinition definition,
            String reason,
            String createdBy,
            Instant createdTime) {
        this(id, ruleId, version, definition, reason, createdBy, createdTime, null);
    }
}
