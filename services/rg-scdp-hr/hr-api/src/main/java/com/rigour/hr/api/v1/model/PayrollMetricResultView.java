package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollMetricResultView(
        String code,
        String name,
        BigDecimal actual,
        BigDecimal target,
        BigDecimal weight,
        BigDecimal completion,
        BigDecimal coefficient,
        BigDecimal contribution,
        String evidence) {}
