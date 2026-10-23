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
        BigDecimal cap,
        String cityMetric,
        String scoringMode) {
    public PayrollMetricDefinition(
            String code,
            String name,
            String source,
            BigDecimal target,
            BigDecimal weight,
            List<PayrollMetricStep> steps,
            BigDecimal cap,
            String cityMetric) {
        this(code, name, source, target, weight, steps, cap, cityMetric, null);
    }

    public PayrollMetricDefinition(
            String code,
            String name,
            String source,
            BigDecimal target,
            BigDecimal weight,
            List<PayrollMetricStep> steps,
            BigDecimal cap) {
        this(code, name, source, target, weight, steps, cap, null, null);
    }
}
