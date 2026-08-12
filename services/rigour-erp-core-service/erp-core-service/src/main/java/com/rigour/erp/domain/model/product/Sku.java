package com.rigour.erp.domain.model.product;

import java.math.BigDecimal;

/** 来源商品下的一组可销售规格组合。 */
public record Sku(
        /** 订货宝多规格项唯一标识。 */
        String sourceId,
        /** 订货宝 SKU 编码。 */
        String code,
        /** 订货宝 SKU 条码。 */
        String barcode,
        /** 第一规格值来源 ID，用于建立 SKU 与规格值关系。 */
        String firstSpecificationValueSourceId,
        /** 第二规格值来源 ID，用于建立 SKU 与规格值关系。 */
        String secondSpecificationValueSourceId,
        /** 来源规格组合展示名称。 */
        String specificationName,
        /** 订货宝 options_id。 */
        String optionsId,
        /** 基础单位订货价。 */
        BigDecimal orderPrice,
        /** 基础单位销售/市场价。 */
        BigDecimal marketPrice,
        /** 基础单位采购价。 */
        BigDecimal purchasePrice,
        /** 中包装订货价。 */
        BigDecimal middleOrderPrice,
        /** 大包装订货价。 */
        BigDecimal bigOrderPrice,
        /** 中包装条码。 */
        String middleBarcode,
        /** 大包装条码。 */
        String bigBarcode,
        /** 归一化 SKU 字段 SHA-256。 */
        String payloadHash) {
    public Sku(String sourceId, String code, String barcode,
               String firstSpecificationValueSourceId, String secondSpecificationValueSourceId,
               String specificationName, String payloadHash) {
        this(sourceId, code, barcode, firstSpecificationValueSourceId,
                secondSpecificationValueSourceId, specificationName, null, null, null, null,
                null, null, null, null, payloadHash);
    }
}
