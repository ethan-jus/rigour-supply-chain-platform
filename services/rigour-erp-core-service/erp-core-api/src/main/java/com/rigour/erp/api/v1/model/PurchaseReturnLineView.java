package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** ERP 采购退货单详情明细；字段方向与 Integration 的订货宝标准化明细保持一致。 */
public record PurchaseReturnLineView(
        /** 订货宝明细主键。 */ String sourceLineId,
        /** 订货宝商品主键。 */ String sourceGoodsId,
        /** 商品编码。 */ String goodsCode,
        /** 商品名称。 */ String goodsName,
        /** 规格组合主键。 */ String optionsId,
        /** 规格商品编码。 */ String optionsGoodsCode,
        /** 规格组合展示文本。 */ String optionsSummary,
        /** 申请退货数量。 */ BigDecimal requestedQuantity,
        /** 确认退货数量。 */ BigDecimal confirmedQuantity,
        /** 申请退货单价。 */ BigDecimal returnPrice,
        /** 确认退货单价。 */ BigDecimal confirmedPrice,
        /** 计量单位编码。 */ String unitCode,
        /** 计量单位名称。 */ String unitName,
        /** 申请单位数量。 */ BigDecimal unitQuantity,
        /** 确认退货单位数量。 */ BigDecimal confirmedUnitQuantity,
        /** 换算数量。 */ BigDecimal conversionNumber,
        /** 退货金额。 */ BigDecimal amount,
        /** 成本价。 */ BigDecimal costPrice,
        /** 关联采购单号。 */ String purchaseOrderNo,
        /** 分类名称。 */ String categoryName,
        /** 品牌名称。 */ String brandName,
        /** 明细备注。 */ String remark,
        /** 订货宝明细原始字段。 */ Map<String, Object> sourceFields) {
    public PurchaseReturnLineView {
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
