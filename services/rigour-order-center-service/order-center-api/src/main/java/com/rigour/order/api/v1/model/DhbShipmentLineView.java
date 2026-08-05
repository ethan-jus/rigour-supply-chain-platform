package com.rigour.order.api.v1.model;

import java.math.BigDecimal;

/** 发货单商品明细本地投影。 */
public record DhbShipmentLineView(
        /** 来源明细ID；来源无ID时为Integration生成的稳定键。 */ String lineId,
        /** 来源商品ERP外码goods_guid；缺失时兼容goods_id。 */ String productGuid,
        /** 规格商品编码options_goods_num。 */ String skuNo,
        /** 商品编码goods_num。 */ String productCode,
        /** 商品名称goods_name快照。 */ String productName,
        /** 本次发货数量ships_number，沿用来源小单位。 */ BigDecimal quantity,
        /** orders_list_info.orders_price/order_units_price来源单价。 */ BigDecimal unitPrice,
        /** orders_list_info.actual_amount来源明细金额；来源无值时为空。 */ BigDecimal amount,
        /** orders_list_info.order_units_name/base_units_name来源单位。 */ String unit,
        /** 主单stock_num出库仓库编号。 */ String warehouseNo,
        /** 发货明细备注。 */ String remark) {
}
