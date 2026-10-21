package com.rigour.hr.api.v1.model;

import java.time.Instant;

public record PayrollSchemeVersionView(
        String id,
        String schemeId,
        int version,
        int revision,
        String status,
        PayrollSchemeDefinition definition,
        String reason,
        String createdBy,
        String publishedBy,
        Instant updatedTime,
        Instant publishedTime,
        java.time.LocalDate stopFrom) {
    public PayrollSchemeVersionView(
            String id,
            String schemeId,
            int version,
            int revision,
            String status,
            PayrollSchemeDefinition definition,
            String reason,
            String createdBy,
            String publishedBy,
            Instant updatedTime,
            Instant publishedTime) {
        this(
                id,
                schemeId,
                version,
                revision,
                status,
                definition,
                reason,
                createdBy,
                publishedBy,
                updatedTime,
                publishedTime,
                null);
    }
}
