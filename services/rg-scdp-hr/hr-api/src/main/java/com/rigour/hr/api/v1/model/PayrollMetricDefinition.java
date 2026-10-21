package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollMetricDefinition(
        String code,
        String name,
        String source,
        BigDecimal target,
        BigDecimal weight,
        List<PayrollMetricStep> steps,
        BigDecimal cap) {}
