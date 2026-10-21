package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;

public record ManagerMetricResult(
        String code,
        BigDecimal actual,
        BigDecimal target,
        BigDecimal completion,
        BigDecimal coefficient,
        BigDecimal contribution) {}
