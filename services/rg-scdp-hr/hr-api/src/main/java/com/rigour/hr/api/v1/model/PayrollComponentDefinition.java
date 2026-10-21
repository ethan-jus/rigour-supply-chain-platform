package com.rigour.hr.api.v1.model;

import java.time.LocalDate;
import java.util.List;

public record PayrollComponentDefinition(
        String name,
        String kind,
        String role,
        List<Long> departmentIds,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        PayrollSalaryStandard salary,
        PayrollPerformanceStandard performance,
        PayrollSchemeCommissionPolicy commission,
        PayrollWorkCalendar calendar,
        PayrollAllowanceStandard allowance,
        List<String> employeeCodes) {
    public PayrollComponentDefinition(
            String name,
            String kind,
            String role,
            List<Long> departmentIds,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            PayrollSalaryStandard salary,
            PayrollPerformanceStandard performance,
            PayrollSchemeCommissionPolicy commission,
            PayrollWorkCalendar calendar) {
        this(
                name,
                kind,
                role,
                departmentIds,
                effectiveFrom,
                effectiveTo,
                salary,
                performance,
                commission,
                calendar,
                null,
                List.of());
    }
}
