package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;
import java.util.List;

public record ManagerMetric(
        String code,
        String name,
        BigDecimal actual,
        BigDecimal target,
        BigDecimal weight,
        List<PerformanceBand> bands,
        BigDecimal cap,
        boolean targetProrated,
        String scoringMode) {
    public ManagerMetric(
            String code,
            String name,
            BigDecimal actual,
            BigDecimal target,
            BigDecimal weight,
            List<PerformanceBand> bands,
            BigDecimal cap,
            boolean targetProrated) {
        this(code, name, actual, target, weight, bands, cap, targetProrated, null);
    }

    public ManagerMetric(
            String code,
            String name,
            BigDecimal actual,
            BigDecimal target,
            BigDecimal weight,
            List<PerformanceBand> bands,
            BigDecimal cap) {
        this(code, name, actual, target, weight, bands, cap, false, null);
    }
}
