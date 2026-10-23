package com.rigour.hr.api.v1.model;

public record PayrollComponentActionResult(
        PayrollComponentVersionView rule,
        int attendanceReviews,
        int payrollReviews,
        String action,
        String reason) {}
