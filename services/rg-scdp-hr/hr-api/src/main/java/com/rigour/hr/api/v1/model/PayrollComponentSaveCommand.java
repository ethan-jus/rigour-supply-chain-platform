package com.rigour.hr.api.v1.model;

public record PayrollComponentSaveCommand(
        String requestId,
        String previousId,
        PayrollComponentDefinition definition,
        String reason,
        boolean replacePending,
        Integer revision) {
    public PayrollComponentSaveCommand(
            String requestId,
            String previousId,
            PayrollComponentDefinition definition,
            String reason) {
        this(requestId, previousId, definition, reason, false, null);
    }
}
