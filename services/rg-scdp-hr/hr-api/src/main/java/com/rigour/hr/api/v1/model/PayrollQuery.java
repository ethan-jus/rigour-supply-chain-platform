package com.rigour.hr.api.v1.model;

public record PayrollQuery(
        int begin, int step, String month, String keyword, Long departmentId, String statusCode) {}
