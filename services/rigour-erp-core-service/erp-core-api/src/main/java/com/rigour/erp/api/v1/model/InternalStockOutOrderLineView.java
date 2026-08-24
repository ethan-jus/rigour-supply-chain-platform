package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 出库单明细视图；销售出库和调拨出库共用该结构。 */
public record InternalStockOutOrderLineView(
        Long id,
        Integer lineNo,
        Long salesOrderLineId,
        Long transferOrderLineId,
        Long productId,
        Long productVariantId,
        String productCodeSnapshot,
        String variantCodeSnapshot,
        String productNameSnapshot,
        String unitCode,
        BigDecimal quantity,
        String remark) {
}
