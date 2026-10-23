package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

public record HrExpenseQuery(
        int begin,
        int step,
        String documentNo,
        String title,
        String remark,
        String typeCode,
        String employeeCode,
        String cityCode,
        String statusCode,
        String paymentStatusCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        String costMonth) {
    public HrExpenseQuery(
            int begin,
            int step,
            String documentNo,
            String title,
            String remark,
            String typeCode,
            String employeeCode,
            String cityCode,
            String statusCode,
            String paymentStatusCode,
            LocalDate dateFrom,
            LocalDate dateTo) {
        this(
                begin,
                step,
                documentNo,
                title,
                remark,
                typeCode,
                employeeCode,
                cityCode,
                statusCode,
                paymentStatusCode,
                dateFrom,
                dateTo,
                null);
    }
}
