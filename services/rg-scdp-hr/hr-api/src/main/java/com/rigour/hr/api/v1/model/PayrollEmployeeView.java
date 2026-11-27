package com.rigour.hr.api.v1.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
        Long id,
        @JsonIgnore String employmentStatus) {
    public PayrollEmployeeView(
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
        this(employeeCode, employeeName, departmentId, departmentName, cityName, entryDate, leaveDate,
                payrollCategory, revision, id, null);
    }

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
                null,
                null);
    }
}
