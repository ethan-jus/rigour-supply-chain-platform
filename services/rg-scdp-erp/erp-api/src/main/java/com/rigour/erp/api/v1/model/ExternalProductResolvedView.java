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
        /** 商品中包装单位；明细表按「数量(箱)」下单时用它决定内部单位。 */
        String middleUnitCode,
        String matchedSourceSystem,
        String matchStrategy,
        Integer matchScore,
        String status,
        String message) {
}
