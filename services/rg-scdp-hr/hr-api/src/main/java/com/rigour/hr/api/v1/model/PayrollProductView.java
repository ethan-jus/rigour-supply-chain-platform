package com.rigour.hr.api.v1.model;

public record PayrollProductView(
        Long productId,
        Long productVariantId,
        String productCode,
        String variantCode,
        String productName,
        String specification,
        String unitCode) {}
