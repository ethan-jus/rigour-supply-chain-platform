package com.rigour.order.api.v1.model;

import java.math.BigDecimal;

/** 销售订单商品明细保存命令；商品和规格来自 ERP 商品列表。 */
public record SalesOrderLineCommand(
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
        String remark) {
}
