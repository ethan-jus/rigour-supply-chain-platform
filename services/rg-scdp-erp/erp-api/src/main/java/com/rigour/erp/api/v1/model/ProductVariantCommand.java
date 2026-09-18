package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** 商品规格价格入参；新增规格编码由后端统一生成。 */
public record ProductVariantCommand(
        Long id,
        String specificationSnapshot,
        String unitCode,
        BigDecimal salePrice,
        BigDecimal marketPrice,
        BigDecimal purchasePrice,
        BigDecimal minOrderQuantity,
        BigDecimal orderMultipleQuantity,
        BigDecimal limitQuantity,
        Boolean defaultFlag,
        String remark) {
}
