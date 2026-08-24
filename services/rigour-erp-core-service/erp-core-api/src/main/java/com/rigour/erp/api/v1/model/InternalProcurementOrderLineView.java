package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 采购订单明细视图；商品名称、编码和单位为下单时快照。 */
public record InternalProcurementOrderLineView(
        Long id,
        Integer lineNo,
        Long productId,
        Long productVariantId,
        String productCode,
        String variantCode,
        String productName,
        String unitCode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineAmount,
        BigDecimal receivedQuantity,
        String remark) {
}
