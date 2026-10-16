package com.rigour.hr.domain.payroll;

import java.time.LocalDate;

public record SalesVisitEvidence(
        String id,
        LocalDate date,
        String customer,
        String status,
        Long durationMillis,
        boolean durationComplete) {}
