package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollSchemeFactsCommand(
        Integer revision,
        BigDecimal performanceBase,
        List<PayrollMetricActual> metrics,
        String reason) {}
