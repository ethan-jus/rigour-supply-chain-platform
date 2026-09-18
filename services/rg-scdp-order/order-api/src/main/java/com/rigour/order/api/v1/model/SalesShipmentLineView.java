package com.rigour.order.api.v1.model;

import java.math.BigDecimal;

/** 销售发货单明细视图。 */
public record SalesShipmentLineView(
        Long id,
        Long salesOrderLineId,
        Integer lineNo,
        Long productId,
        Long productVariantId,
        String productCodeSnapshot,
        String skuCodeSnapshot,
        String productNameSnapshot,
        String specificationSnapshot,
        String unitCode,
        BigDecimal shippedQuantity,
        String remark) {
}
