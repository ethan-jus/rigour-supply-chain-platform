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
        BigDecimal cap) {}
