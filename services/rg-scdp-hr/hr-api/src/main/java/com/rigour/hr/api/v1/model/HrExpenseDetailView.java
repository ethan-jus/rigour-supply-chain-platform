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
        Instant updatedTime) {}
