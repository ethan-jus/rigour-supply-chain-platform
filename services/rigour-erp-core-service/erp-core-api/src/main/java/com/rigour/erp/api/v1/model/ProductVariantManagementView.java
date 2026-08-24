package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 商品规格价格视图。 */
public record ProductVariantManagementView(
        Long id,
        String variantCode,
        String specificationSnapshot,
        String unitCode,
        BigDecimal salePrice,
        BigDecimal marketPrice,
        BigDecimal purchasePrice,
        BigDecimal minOrderQuantity,
        BigDecimal orderMultipleQuantity,
        BigDecimal limitQuantity,
        Boolean defaultFlag,
        String remark,
        Integer revision,
        Instant updatedTime) {
}
