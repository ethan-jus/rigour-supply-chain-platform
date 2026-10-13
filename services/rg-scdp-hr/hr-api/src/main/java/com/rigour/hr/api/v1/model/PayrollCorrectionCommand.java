package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollCorrectionCommand(
        String requestId, int revision, String targetMonth, BigDecimal amount, String reason) {}
