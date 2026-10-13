package com.rigour.hr.api.v1.model;

public record HrCostEmployeeView(
        String employeeCode, String employeeName, Long departmentId, String departmentName) {}
