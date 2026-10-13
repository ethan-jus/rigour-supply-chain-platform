package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

public record HrExpenseQuery(
        int begin,
        int step,
        String keyword,
        String typeCode,
        String employeeCode,
        String cityCode,
        String statusCode,
        String paymentStatusCode,
        LocalDate dateFrom,
        LocalDate dateTo) {}
