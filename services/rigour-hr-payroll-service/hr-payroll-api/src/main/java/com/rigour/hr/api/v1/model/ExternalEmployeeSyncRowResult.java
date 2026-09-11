package com.rigour.hr.api.v1.model;

/** 外部员工单行同步结果。 */
public record ExternalEmployeeSyncRowResult(
        String sourceEmployeeId,
        String employeeCode,
        String status,
        String message) {
}
