package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 调拨单明细保存命令。 */
public record InternalTransferOrderLineCommand(
        Long productId,
        Long productVariantId,
        BigDecimal quantity,
        String remark) {
}
