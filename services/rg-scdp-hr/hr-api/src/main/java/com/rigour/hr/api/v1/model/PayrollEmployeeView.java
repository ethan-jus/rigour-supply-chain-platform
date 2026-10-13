package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

public record PayrollEmployeeView(
        String employeeCode,
        String employeeName,
        Long departmentId,
        String departmentName,
        String cityName,
        LocalDate entryDate,
        LocalDate leaveDate,
        String payrollCategory,
        Integer revision,
        Long id) {
    public PayrollEmployeeView(
            String employeeCode,
            String employeeName,
            Long departmentId,
            String departmentName,
            String cityName,
            LocalDate entryDate,
            LocalDate leaveDate,
            String payrollCategory) {
        this(
                employeeCode,
                employeeName,
                departmentId,
                departmentName,
                cityName,
                entryDate,
                leaveDate,
                payrollCategory,
                null,
                null);
    }
}
