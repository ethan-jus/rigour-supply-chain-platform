package com.rigour.hr.api.v1.model;

import java.time.LocalDate;
import java.util.List;

public record PayrollPreparationEmployeeView(
        String employeeCode,
        String employeeName,
        Long departmentId,
        String departmentName,
        String payrollCategory,
        String payrollStatus,
        String payrollId,
        boolean locked,
        List<String> profileIssues,
        List<String> ruleIssues,
        Integer revision,
        Long employeeId,
        Boolean regularized,
        LocalDate regularizationDate,
        LocalDate salaryEffectiveFrom) {}
