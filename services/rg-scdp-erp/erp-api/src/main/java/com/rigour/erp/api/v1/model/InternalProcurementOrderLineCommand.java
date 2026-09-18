package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 采购订单明细入参；商品信息由 productId/productVariantId 回填快照。 */
public record InternalProcurementOrderLineCommand(
        Long productId,
        Long productVariantId,
        BigDecimal quantity,
        BigDecimal unitPrice,
        String remark) {
}
