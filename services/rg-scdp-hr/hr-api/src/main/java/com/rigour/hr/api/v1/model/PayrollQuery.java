package com.rigour.hr.api.v1.model;

public record PayrollQuery(
        int begin,
        int step,
        String month,
        String keyword,
        Long departmentId,
        String statusCode,
        String payrollCategory) {
    public PayrollQuery(
            int begin, int step, String month, String keyword, Long departmentId, String statusCode) {
        this(begin, step, month, keyword, departmentId, statusCode, null);
    }
}
