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
        List<PayrollCorrectionView> correctionsIssued,
        String paymentBlockReason,
        String createdBy, String createdByName, Instant createdTime) {
    public PayrollDetailView(String id, String month, String employeeCode, String employeeName, Long departmentId, String departmentName, String cityName, String statusCode, int revision, PayrollCalculationView calculation, Instant updatedTime, List<PayrollPaymentView> payments, List<PayrollCorrectionView> correctionsIssued, String paymentBlockReason) {
        this(id, month, employeeCode, employeeName, departmentId, departmentName, cityName, statusCode, revision, calculation, updatedTime, payments, correctionsIssued, paymentBlockReason, null, null, null);
    }

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
            Instant updatedTime,
            List<PayrollPaymentView> payments,
            List<PayrollCorrectionView> correctionsIssued) {
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
                payments,
                correctionsIssued,
                null);
    }

    public PayrollDetailView withPaymentBlockReason(String reason) {
        return new PayrollDetailView(
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
                payments,
                correctionsIssued,
                reason, createdBy, createdByName, createdTime);
    }

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
