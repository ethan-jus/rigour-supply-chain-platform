package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 采购单详情明细；字段方向与 Integration 的订货宝标准化明细保持一致。 */
public record PurchaseOrderLineView(
        /** 订货宝明细主键。 */ String sourceLineId,
        /** 订货宝商品主键。 */ String sourceGoodsId,
        /** 订货宝商品 GUID。 */ String sourceGoodsGuid,
        /** 商品编码。 */ String goodsCode,
        /** 商品名称。 */ String goodsName,
        /** 规格组合主键。 */ String optionsId,
        /** 规格商品编码。 */ String optionsGoodsCode,
        /** 规格组合展示文本。 */ String optionsSummary,
        /** 基础单位数量。 */ BigDecimal baseQuantity,
        /** 采购单价。 */ BigDecimal unitPrice,
        /** 采购单位编码。 */ String purchaseUnitCode,
        /** 采购单位名称。 */ String purchaseUnitName,
        /** 采购单位数量。 */ BigDecimal purchaseUnitQuantity,
        /** 已入库数量。 */ BigDecimal warehousedQuantity,
        /** 已退货数量。 */ BigDecimal returnedQuantity,
        /** 明细备注。 */ String remark) { }
