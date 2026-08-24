package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** 外部来源出库投影明细；销售出库可携带销售订单明细，调拨出库可携带调拨明细。 */
public record ExternalStockOutProjectionLineCommand(
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
