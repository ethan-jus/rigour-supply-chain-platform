package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollPaymentCommand(
        String requestId,
        int revision,
        LocalDate paidDate,
        BigDecimal amount,
        String referenceNo,
        String reason) {}
