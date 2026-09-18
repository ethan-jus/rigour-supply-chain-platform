package com.rigour.order.api.v1.model;

import java.math.BigDecimal;

/** 销售订单商品明细。 */
public record SalesOrderLineView(
        Long id,
        Integer lineNo,
        Long productId,
        Long productVariantId,
        String productCodeSnapshot,
        String skuCodeSnapshot,
        String productNameSnapshot,
        String specificationSnapshot,
        String unitCode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discountRate,
        BigDecimal discountAmount,
        BigDecimal lineAmount,
        String remark) {
}
