package com.rigour.integration.api.v1.model;

import java.math.BigDecimal;
import java.util.Map;

/** Integration 归一化采购单明细。 */
public record DhbPurchaseOrderLineView(
        /** 订货宝采购明细主键。 */ String sourceLineId,
        /** 订货宝商品主键。 */ String sourceGoodsId,
        /** 订货宝商品 GUID。 */ String sourceGoodsGuid,
        /** 商品编码。 */ String goodsCode,
        /** 商品名称。 */ String goodsName,
        /** 规格组合主键。 */ String optionsId,
        /** 规格商品编码。 */ String optionsGoodsCode,
        /** 规格组合展示文本。 */ String optionsSummary,
        /** 基础单位采购数量。 */ BigDecimal baseQuantity,
        /** 采购单价。 */ BigDecimal unitPrice,
        /** 采购单位编码。 */ String purchaseUnitCode,
        /** 采购单位名称。 */ String purchaseUnitName,
        /** 采购单位数量。 */ BigDecimal purchaseUnitQuantity,
        /** 已入库数量。 */ BigDecimal warehousedQuantity,
        /** 已退货数量。 */ BigDecimal returnedQuantity,
        /** 明细备注。 */ String remark,
        /** 未归一化的扩展来源字段。 */ Map<String, Object> sourceFields) { }
