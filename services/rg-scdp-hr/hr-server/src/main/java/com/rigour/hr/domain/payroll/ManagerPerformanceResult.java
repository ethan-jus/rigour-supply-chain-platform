package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;
import java.util.List;

public record ManagerPerformanceResult(
        BigDecimal amount,
        BigDecimal coefficient,
        BigDecimal weightedCompletion,
        int protectedDays,
        int assessedDays,
        List<ManagerMetricResult> metrics,
        List<String> issues) {}
