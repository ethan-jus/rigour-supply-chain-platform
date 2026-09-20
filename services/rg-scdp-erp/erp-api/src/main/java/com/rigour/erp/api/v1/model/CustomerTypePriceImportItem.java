package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** 客户类型等级价导入明细；导入只写入提交的规格与客户类型，不影响其他等级价。 */
public record CustomerTypePriceImportItem(
        Long productVariantId,
        String customerTypeCode,
        BigDecimal salePrice,
        String remark) {
}
