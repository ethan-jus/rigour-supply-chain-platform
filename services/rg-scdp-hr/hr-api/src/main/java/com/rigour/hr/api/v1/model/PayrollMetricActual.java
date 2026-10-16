package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollMetricActual(String code, BigDecimal value, String evidence) {}
