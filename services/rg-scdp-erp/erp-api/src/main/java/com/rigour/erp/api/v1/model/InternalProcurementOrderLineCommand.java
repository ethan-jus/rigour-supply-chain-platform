package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 采购明细；新请求按商品和SKU编码关联，旧ID仅用于兼容已有调用方。 */
public record InternalProcurementOrderLineCommand(
        Long productId,
        Long productVariantId,
        BigDecimal quantity,
        BigDecimal unitPrice,
        String remark,
        String productCode,
        String variantCode,
        String unitCode) {
    public InternalProcurementOrderLineCommand(
            Long productId,
            Long productVariantId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String remark) {
        this(productId, productVariantId, quantity, unitPrice, remark, null, null, null);
    }
}
