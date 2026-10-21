package com.rigour.sales.api.v1.model;

import java.time.LocalDate;

public record SalesPayrollVisitView(
        String sourceId,
        LocalDate date,
        String customerKey,
        String reviewStatus,
        Long verifiedCommunicationMillis,
        boolean durationComplete,
        java.time.LocalDateTime submittedAt) {
    public SalesPayrollVisitView(
            String sourceId,
            LocalDate date,
            String customerKey,
            String reviewStatus,
            Long verifiedCommunicationMillis,
            boolean durationComplete) {
        this(
                sourceId,
                date,
                customerKey,
                reviewStatus,
                verifiedCommunicationMillis,
                durationComplete,
                null);
    }
}
