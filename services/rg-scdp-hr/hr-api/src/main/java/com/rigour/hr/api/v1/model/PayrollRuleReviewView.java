package com.rigour.hr.api.v1.model;

public record PayrollRuleReviewView(
        String id,
        String subject,
        String subjectId,
        String employeeCode,
        String employeeName,
        String month,
        String ruleName,
        String action,
        String reason,
        java.time.Instant time) {}
