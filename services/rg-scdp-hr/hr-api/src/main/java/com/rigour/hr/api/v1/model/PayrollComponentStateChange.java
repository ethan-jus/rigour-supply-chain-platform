package com.rigour.hr.api.v1.model;

import java.time.Instant;
import java.util.List;

public record PayrollComponentStateChange(
        int revision,
        String effectiveMonth,
        boolean enabled,
        String reason,
        String actor,
        Instant time,
        boolean retired,
        List<Long> replacedDepartmentIds) {
    public PayrollComponentStateChange {
        replacedDepartmentIds =
                replacedDepartmentIds == null ? List.of() : List.copyOf(replacedDepartmentIds);
    }

    public PayrollComponentStateChange(
            int revision,
            String effectiveMonth,
            boolean enabled,
            String reason,
            String actor,
            Instant time) {
        this(revision, effectiveMonth, enabled, reason, actor, time, false, List.of());
    }
}
