package com.rigour.hr.application.model;

/** Monthly assignment or explicit HR override, without inferring employment history. */
public record PayrollPreparationDepartment(String employeeCode, Long departmentId) {}
