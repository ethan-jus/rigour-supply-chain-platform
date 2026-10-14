package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollCityProductRule(
        Long productId,
        Long productVariantId,
        String productName,
        String unitCode,
        Long categoryId,
        String categoryName,
        String methodCode,
        BigDecimal rate) {}
