package com.rigour.order.api.v1.model;

import java.math.BigDecimal;

/** 订货宝订单明细的可迁移规范化投影。 */
public record DhbOrderLineView(
        /** 来源明细ID。 */
        String lineId,
        /** 来源商品GUID。 */
        String productGuid,
        /** SKU编号。 */
        String skuNo,
        /** 订货宝商品选项编号。 */
        String optionsGoodsNum,
        /** 订货宝商品条码。 */
        String optionsBarcode,
        /** 商品名称。 */
        String productName,
        /** 商品编码。 */
        String coding,
        /** 第一层规格。 */
        String multiFirst,
        /** 第二层规格。 */
        String multiSecond,
        /** 规格组合名称。 */
        String multiName,
        /** 单价。 */
        BigDecimal unitPrice,
        /** 数量。 */
        BigDecimal quantity,
        /** 来源明细金额。 */
        BigDecimal lineAmount,
        /** 计量单位。 */
        String unit,
        /** 明细备注。 */
        String remark,
        BigDecimal purchasePrice,
        BigDecimal conversionNumber,
        BigDecimal offerPrice,
        BigDecimal actualAmount,
        BigDecimal goodsWeight,
        String preSale,
        String contentType,
        String invoiceTax,
        BigDecimal contentPercent) {
}
