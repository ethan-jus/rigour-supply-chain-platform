package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 库存余额列表视图；库存数量只读展示，业务变动必须通过出入库单写入。 */
public record InternalStockBalanceView(
        Long id,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        Long productId,
        String productCode,
        String productName,
        Long productVariantId,
        String variantCode,
        String specificationSnapshot,
        String unitCode,
        BigDecimal availableQuantity,
        BigDecimal lockedQuantity,
        BigDecimal inTransitQuantity,
        Integer revision,
        Instant updatedTime) {
}
