package com.rigour.integration.api.v1.model;

import java.math.BigDecimal;
import java.util.Map;

/** Integration 归一化入库明细。 */
public record DhbWarehousingLineView(
        /** 订货宝入库明细主键。 */ String sourceLineId,
        /** 订货宝商品主键。 */ String sourceGoodsId,
        /** 商品编码。 */ String goodsCode,
        /** 商品名称。 */ String goodsName,
        /** 规格组合主键。 */ String optionsId,
        /** 规格商品编码。 */ String optionsGoodsCode,
        /** 规格组合展示文本。 */ String optionsSummary,
        /** 基础单位入库数量。 */ BigDecimal baseQuantity,
        /** 入库单位数量。 */ BigDecimal unitQuantity,
        /** 计量单位编码。 */ String unitCode,
        /** 计量单位名称。 */ String unitName,
        /** 单位换算数量。 */ BigDecimal conversionNumber,
        /** 成本单价。 */ BigDecimal costPrice,
        /** 入库单位成本。 */ BigDecimal unitCostPrice,
        /** 采购单价。 */ BigDecimal purchasePrice,
        /** 批发价。 */ BigDecimal wholesalePrice,
        /** 库位。 */ String allocation,
        /** 商品条码。 */ String barcode,
        /** 商品型号。 */ String goodsModel,
        /** 来源实际库存。 */ BigDecimal sourceRealQuantity,
        /** 来源可用库存。 */ BigDecimal sourceAvailableQuantity,
        /** 订货宝协作方主键。 */ String collaboratorSourceId,
        /** 协作方名称。 */ String collaboratorName,
        /** 明细备注。 */ String remark,
        /** 未归一化的扩展来源字段。 */ Map<String, Object> sourceFields) { }
