package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 面向业务方的商品数量投影；Portal 不自行推断数量单位。 */
public record ProductQuantityView(
        /** 业务数量类型：MIN_ORDER、INVENTORY_LOWER、INVENTORY_SAFETY、INVENTORY_UPPER。 */
        String quantityType,
        /** 数量值。 */
        BigDecimal amount,
        /** 数量对应的真实计量单位名称。 */
        String unitName,
        /** ERP 统一业务标签，例如“安全库存”。 */
        String displayLabel,
        /** ERP 统一展示值，例如“100桶”。 */
        String displayValue) {
}
