package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollSchemeAllowance(String code, String name, BigDecimal amount) {}
