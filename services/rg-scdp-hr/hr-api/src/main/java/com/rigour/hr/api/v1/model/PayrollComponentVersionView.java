package com.rigour.hr.api.v1.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record PayrollComponentVersionView(
        String id,
        String ruleId,
        int version,
        PayrollComponentDefinition definition,
        String reason,
        String createdBy,
        Instant createdTime,
        String ruleCode,
        int revision,
        boolean deleted,
        List<PayrollComponentStateChange> stateChanges) {
    public PayrollComponentVersionView {
        stateChanges = stateChanges == null ? List.of() : List.copyOf(stateChanges);
    }

    public boolean enabledAt(LocalDate date) {
        String month = date.toString().substring(0, 7);
        return !deleted
                && stateChanges.stream()
                        .filter(change -> change.effectiveMonth().compareTo(month) <= 0)
                        .max(Comparator.comparingInt(PayrollComponentStateChange::revision))
                        .map(PayrollComponentStateChange::enabled)
                        .orElse(true);
    }

    public int stateRevision() {
        return stateChanges.stream()
                .mapToInt(PayrollComponentStateChange::revision)
                .max()
                .orElse(0);
    }

    public PayrollComponentVersionView(
            String id,
            String ruleId,
            int version,
            PayrollComponentDefinition definition,
            String reason,
            String createdBy,
            Instant createdTime,
            String ruleCode) {
        this(
                id,
                ruleId,
                version,
                definition,
                reason,
                createdBy,
                createdTime,
                ruleCode,
                1,
                false,
                List.of());
    }

    public PayrollComponentVersionView(
            String id,
            String ruleId,
            int version,
            PayrollComponentDefinition definition,
            String reason,
            String createdBy,
            Instant createdTime) {
        this(
                id,
                ruleId,
                version,
                definition,
                reason,
                createdBy,
                createdTime,
                null,
                1,
                false,
                List.of());
    }
}
