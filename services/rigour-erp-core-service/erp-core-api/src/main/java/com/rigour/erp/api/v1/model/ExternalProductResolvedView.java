package com.rigour.erp.api.v1.model;

/** 外部商品引用解析到的 ERP 商品规格。 */
public record ExternalProductResolvedView(
        String referenceId,
        Long productId,
        Long productVariantId,
        String productCode,
        String variantCode,
        String productName,
        String specification,
        String unitCode,
        String matchedSourceSystem,
        String matchStrategy,
        Integer matchScore,
        String status,
        String message) {
}
