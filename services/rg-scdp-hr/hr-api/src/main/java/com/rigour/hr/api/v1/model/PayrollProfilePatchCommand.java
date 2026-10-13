package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

public record PayrollProfilePatchCommand(
        String employeeCode,
        int revision,
        String payrollCategory,
        Boolean regularized,
        LocalDate regularizationDate,
        LocalDate salaryEffectiveFrom,
        String probationSalary,
        String regularSalary) {}
