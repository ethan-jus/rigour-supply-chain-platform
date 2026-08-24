package com.rigour.order.api.v1.model;

import java.math.BigDecimal;

/** 销售发货单明细保存命令；未传快照字段时服务端会从销售订单明细补齐。 */
public record SalesShipmentLineCommand(
        Long salesOrderLineId,
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
