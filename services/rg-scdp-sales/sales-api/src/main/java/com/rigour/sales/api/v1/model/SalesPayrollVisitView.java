package com.rigour.sales.api.v1.model;

import java.time.LocalDate;

public record SalesPayrollVisitView(
        String sourceId,
        LocalDate date,
        String customerKey,
        String reviewStatus,
        Long verifiedCommunicationMillis,
        boolean durationComplete) {}
