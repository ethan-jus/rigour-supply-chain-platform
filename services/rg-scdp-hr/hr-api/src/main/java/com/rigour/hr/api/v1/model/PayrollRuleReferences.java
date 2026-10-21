package com.rigour.hr.api.v1.model;

public record PayrollRuleReferences(
        String salaryId,
        String performanceId,
        String commissionId,
        String attendanceId,
        String allowanceId) {
    public PayrollRuleReferences(
            String salaryId, String performanceId, String commissionId, String attendanceId) {
        this(salaryId, performanceId, commissionId, attendanceId, null);
    }
}
