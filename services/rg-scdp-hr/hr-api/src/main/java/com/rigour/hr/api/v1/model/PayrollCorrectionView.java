package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

public record PayrollCorrectionView(
        String id,
        String sourcePayrollId,
        String sourceMonth,
        String targetMonth,
        BigDecimal amount,
        String reason,
        String statusCode,
        int revision,
        Instant createdTime) {}
