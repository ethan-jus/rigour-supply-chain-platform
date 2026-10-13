package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HrExpenseCommand(
        String requestId,
        Integer revision,
        String title,
        String typeCode,
        String employeeCode,
        String cityCode,
        String cityName,
        LocalDate costDate,
        BigDecimal amount,
        String paymentStatusCode,
        String remark,
        String reason) {}
