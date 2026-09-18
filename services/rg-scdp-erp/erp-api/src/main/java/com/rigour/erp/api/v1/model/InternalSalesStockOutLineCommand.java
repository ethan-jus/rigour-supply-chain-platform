package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 销售出库确认明细；商品快照来自销售订单明细，避免后续商品改名影响历史单据。 */
public record InternalSalesStockOutLineCommand(
        Long salesOrderLineId,
        Long productId,
        Long productVariantId,
        String productCodeSnapshot,
        String variantCodeSnapshot,
        String productNameSnapshot,
        String unitCode,
        BigDecimal quantity,
        String remark) {
}
