package com.rigour.erp.api.v1.model;

/** 外部商品单行同步结果。 */
public record ExternalProductSyncRowResult(
        String sourceProductId,
        Long productId,
        Long productVariantId,
        String productCode,
        String variantCode,
        String unitCode,
        String status,
        String message) {
}
