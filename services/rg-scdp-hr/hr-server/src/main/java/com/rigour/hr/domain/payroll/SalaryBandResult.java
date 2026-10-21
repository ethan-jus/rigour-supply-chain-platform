package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;
import java.util.List;

public record SalaryBandResult(String code, BigDecimal amount, List<String> issues) {}
