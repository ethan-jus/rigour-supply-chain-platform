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
                        .filter(
                                change ->
                                        !change.retired()
                                                && change.effectiveMonth().compareTo(month) <= 0)
                        .max(Comparator.comparingInt(PayrollComponentStateChange::revision))
                        .map(PayrollComponentStateChange::enabled)
                        .orElse(true);
    }

    /** Replacement freezes maintenance, but only replaces its scope from the replacement month. */
    public boolean retired() {
        return stateChanges.stream().anyMatch(PayrollComponentStateChange::retired);
    }

    public boolean enabledForDepartmentAt(Long department, LocalDate date) {
        String month = date.toString().substring(0, 7);
        return enabledAt(date)
                && stateChanges.stream()
                        .noneMatch(
                                change ->
                                        change.retired()
                                                && change.effectiveMonth().compareTo(month) <= 0
                                                && change.replacedDepartmentIds()
                                                        .contains(department));
    }

    public boolean enabledForEmployeeAt(String employee, LocalDate date) {
        String month = date.toString().substring(0, 7);
        // Personal salary families belong to exactly one employee and have no department scope.
        return "SALARY".equals(definition.kind())
                && definition.employeeCodes().contains(employee)
                && enabledAt(date)
                && stateChanges.stream()
                        .noneMatch(
                                change ->
                                        change.retired()
                                                && change.effectiveMonth().compareTo(month) <= 0);
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
