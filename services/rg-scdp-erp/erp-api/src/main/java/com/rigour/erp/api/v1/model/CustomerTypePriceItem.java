package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 客户类型等级价明细；一个客户类型一条价格。 */
public record CustomerTypePriceItem(
        String customerTypeCode,
        BigDecimal salePrice,
        String remark) {
}
