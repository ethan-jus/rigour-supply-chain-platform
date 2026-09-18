package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** 外部来源调拨出库投影明细；商品和规格必须已映射到 ERP 主数据。 */
public record ExternalTransferStockOutProjectionLineCommand(
        Long productId,
        Long productVariantId,
        String productCodeSnapshot,
        String variantCodeSnapshot,
        String productNameSnapshot,
        String unitCode,
        BigDecimal quantity,
        String remark) {
}
