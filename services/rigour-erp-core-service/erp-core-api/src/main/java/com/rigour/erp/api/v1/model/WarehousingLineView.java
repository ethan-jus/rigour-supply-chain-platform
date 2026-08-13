package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** ERP 入库单明细；字段方向与 Integration 的订货宝标准化明细保持一致。 */
public record WarehousingLineView(
        /** 订货宝明细主键。 */ String sourceLineId,
        /** 订货宝商品主键。 */ String sourceGoodsId,
        /** 商品编码。 */ String goodsCode,
        /** 商品名称。 */ String goodsName,
        /** 规格组合主键。 */ String optionsId,
        /** 规格商品编码。 */ String optionsGoodsCode,
        /** 规格组合展示文本。 */ String optionsSummary,
        /** 基础单位数量。 */ BigDecimal baseQuantity,
        /** 入库单位数量。 */ BigDecimal unitQuantity,
        /** 计量单位编码。 */ String unitCode,
        /** 计量单位名称。 */ String unitName,
        /** 换算数量。 */ BigDecimal conversionNumber,
        /** 成本价。 */ BigDecimal costPrice,
        /** 单位成本价。 */ BigDecimal unitCostPrice,
        /** 采购价。 */ BigDecimal purchasePrice,
        /** 批发价。 */ BigDecimal wholesalePrice,
        /** 分配信息。 */ String allocation,
        /** 条码。 */ String barcode,
        /** 商品型号。 */ String goodsModel,
        /** 来源实际库存。 */ BigDecimal sourceRealQuantity,
        /** 来源可用库存。 */ BigDecimal sourceAvailableQuantity,
        /** 协作方来源主键。 */ String collaboratorSourceId,
        /** 协作方名称。 */ String collaboratorName,
        /** 明细备注。 */ String remark,
        /** 订货宝明细原始字段。 */ Map<String, Object> sourceFields) {
    public WarehousingLineView {
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
