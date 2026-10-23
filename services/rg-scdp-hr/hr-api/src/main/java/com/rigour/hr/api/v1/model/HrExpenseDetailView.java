package com.rigour.hr.api.v1.model;

import java.time.Instant;

public record HrExpenseDetailView(
        String id,
        String documentNo,
        String statusCode,
        int revision,
        HrExpenseCommand data,
        String employeeName,
        Long departmentId,
        String departmentName,
        Instant updatedTime,
        String createdBy,
        String updatedBy,
        Instant createdTime) {
    public HrExpenseDetailView(
            String id,
            String documentNo,
            String statusCode,
            int revision,
            HrExpenseCommand data,
            String employeeName,
            Long departmentId,
            String departmentName,
            Instant updatedTime) {
        this(
                id,
                documentNo,
                statusCode,
                revision,
                data,
                employeeName,
                departmentId,
                departmentName,
                updatedTime,
                null,
                null,
                null);
    }
}
