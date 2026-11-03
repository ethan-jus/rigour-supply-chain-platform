package com.rigour.hr.api.v1.model;

public record PayrollQuery(
        int begin,
        int step,
        String month,
        String keyword,
        Long departmentId,
        String statusCode,
        String payrollCategory,
        String employeeName,
        String employeeCode,
        java.util.List<String> payrollIds) {
    public PayrollQuery(
            int begin, int step, String month, String keyword, Long departmentId,
            String statusCode, String payrollCategory, String employeeName, String employeeCode) {
        this(begin, step, month, keyword, departmentId, statusCode, payrollCategory,
                employeeName, employeeCode, null);
    }
    public PayrollQuery(
            int begin, int step, String month, String keyword, Long departmentId,
            String statusCode, String payrollCategory) {
        this(begin, step, month, keyword, departmentId, statusCode, payrollCategory, null, null);
    }
    public PayrollQuery(
            int begin, int step, String month, String keyword, Long departmentId, String statusCode) {
        this(begin, step, month, keyword, departmentId, statusCode, null);
    }
}
