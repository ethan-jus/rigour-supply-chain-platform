package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollManagerPolicy(
        List<PayrollMetricDefinition> metrics,
        PayrollProtectionPolicy protection,
        BigDecimal cap,
        List<PayrollMetricStep> steps) {}
