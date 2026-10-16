package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;
import java.util.List;

public record SalesPerformanceResult(
        BigDecimal processBudget,
        BigDecimal processDeduction,
        BigDecimal processPay,
        BigDecimal collectionBudget,
        BigDecimal collectionTarget,
        BigDecimal collections,
        BigDecimal collectionPay,
        BigDecimal total,
        List<SalesPerformanceDay> days,
        List<String> issues) {}
