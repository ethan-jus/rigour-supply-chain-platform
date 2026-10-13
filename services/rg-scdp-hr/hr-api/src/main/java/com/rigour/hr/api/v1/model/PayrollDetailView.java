package com.rigour.hr.api.v1.model;

import java.time.Instant;
import java.util.List;

public record PayrollDetailView(
        String id,
        String month,
        String employeeCode,
        String employeeName,
        Long departmentId,
        String departmentName,
        String cityName,
        String statusCode,
        int revision,
        PayrollCalculationView calculation,
        Instant updatedTime,
        List<PayrollPaymentView> payments,
        List<PayrollCorrectionView> correctionsIssued) {
    public PayrollDetailView(
            String id,
            String month,
            String employeeCode,
            String employeeName,
            Long departmentId,
            String departmentName,
            String cityName,
            String statusCode,
            int revision,
            PayrollCalculationView calculation,
            Instant updatedTime) {
        this(
                id,
                month,
                employeeCode,
                employeeName,
                departmentId,
                departmentName,
                cityName,
                statusCode,
                revision,
                calculation,
                updatedTime,
                List.of(),
                List.of());
    }
}
