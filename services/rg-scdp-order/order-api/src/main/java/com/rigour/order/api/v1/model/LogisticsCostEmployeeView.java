package com.rigour.order.api.v1.model;

/** 费用业务负责人，独立于记录的录入人。 */
public record LogisticsCostEmployeeView(
        String employeeCode, String employeeName, Long departmentId, String departmentName) {}
