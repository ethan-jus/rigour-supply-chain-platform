package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollCommissionPolicyView(
        String code,
        String name,
        String methodCode,
        BigDecimal salesRate,
        BigDecimal managerRate) {}
