package com.rigour.order.domain.model.order;

import java.math.BigDecimal;

/** 平台内部订单明细模型；保留外部商品标识，等待后续映射到ERP商品/SKU。 */
public record OrderLine(
        /** 平台内部明细ID。 */
        String id,
        /** 来源明细ID，用于幂等更新。 */
        String sourceLineId,
        /** 来源商品GUID，后续映射ERP商品。 */
        String sourceProductGuid,
        /** 来源SKU编号。 */
        String skuNo,
        /** 订货宝商品选项编号。 */
        String sourceOptionsGoodsNo,
        /** 订货宝商品条码。 */
        String sourceBarcode,
        /** 商品名称快照。 */
        String productName,
        /** 来源商品编码。 */
        String productCode,
        /** 规格第一层。 */
        String specificationFirst,
        /** 规格第二层。 */
        String specificationSecond,
        /** 规格组合名称。 */
        String specificationName,
        /** 含税/未税语义沿用来源，统一金额规则后再明确。 */
        BigDecimal unitPrice,
        /** 订购数量。 */
        BigDecimal quantity,
        /** 明细金额；若来源未提供则由单价乘数量计算。 */
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

    public OrderLine(String id, String sourceLineId, String sourceProductGuid, String skuNo,
                     String sourceOptionsGoodsNo, String sourceBarcode, String productName,
                     String productCode, String specificationFirst, String specificationSecond,
                     String specificationName, BigDecimal unitPrice, BigDecimal quantity,
                     BigDecimal lineAmount, String unit, String remark) {
        this(id, sourceLineId, sourceProductGuid, skuNo, sourceOptionsGoodsNo, sourceBarcode, productName,
                productCode, specificationFirst, specificationSecond, specificationName, unitPrice, quantity,
                lineAmount, unit, remark, null, null, null, null, null, null, null, null, null);
    }
}
