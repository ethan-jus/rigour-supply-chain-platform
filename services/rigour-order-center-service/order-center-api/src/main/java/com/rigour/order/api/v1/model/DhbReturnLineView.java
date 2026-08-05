package com.rigour.order.api.v1.model;

import java.math.BigDecimal;

/** 退货单商品明细本地投影。 */
public record DhbReturnLineView(
        /** 来源明细ID；来源无ID时为Integration生成的稳定键。 */ String lineId,
        /** 商品ERP外码Guid/TrueGuid。 */ String productGuid,
        /** 规格商品编码OptionsGoodsNum。 */ String skuNo,
        /** 商品编码Coding。 */ String productCode,
        /** 商品名称Name。 */ String productName,
        /** 申请退货数量ReturnsNumber。 */ BigDecimal quantity,
        /** 确认退货数量ReturnsConfirmNumber。 */ BigDecimal confirmedQuantity,
        /** 申请退货单价ReturnsPrice。 */ BigDecimal unitPrice,
        /** 确认退货单价ReturnsConfirmPrice。 */ BigDecimal confirmedPrice,
        /** 退货单位ReturnsUnitsName。 */ String unit,
        /** body.Stock.StockGuid或StockId退货仓库外码/编号。 */ String warehouseNo,
        /** body.Stock.StockName退货仓库名称。 */ String warehouseName,
        /** 退货明细备注ReturnsRemark。 */ String remark) {
}
