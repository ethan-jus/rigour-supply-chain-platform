package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 面向业务方的商品价格投影；Portal 不需要解释订货宝 price1/price2 等来源字段。 */
public record ProductPriceView(
        /** 业务价格类型：ORDER、MARKET、PURCHASE、OTHER。 */
        String priceType,
        /** 计量层级：BASE、MIDDLE、BIG。 */
        String unitLevel,
        /** 价格金额。 */
        BigDecimal amount,
        /** 价格对应的真实计量单位名称。 */
        String unitName,
        /** ERP 统一业务标签，例如“订货价”。 */
        String displayLabel,
        /** ERP 统一展示值，例如“¥15.00/桶”。 */
        String displayValue) {
}
