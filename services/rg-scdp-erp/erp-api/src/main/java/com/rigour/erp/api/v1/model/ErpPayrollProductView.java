package com.rigour.erp.api.v1.model;

public record ErpPayrollProductView(
        Long productId,
        Long productVariantId,
        String productCode,
        String variantCode,
        String productName,
        String specification,
        String unitCode) {}
