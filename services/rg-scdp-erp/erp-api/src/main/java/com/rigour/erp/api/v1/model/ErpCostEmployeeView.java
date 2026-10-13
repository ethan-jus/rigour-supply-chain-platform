package com.rigour.erp.api.v1.model;

public record ErpCostEmployeeView(
        String employeeCode, String employeeName, Long departmentId, String departmentName) {}
