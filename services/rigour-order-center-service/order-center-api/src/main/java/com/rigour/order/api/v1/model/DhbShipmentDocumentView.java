package com.rigour.order.api.v1.model;

import java.time.Instant;

/** 订货宝发货单本地投影。 */
public record DhbShipmentDocumentView(
        /** 订货宝发货单号ships_num。 */ String shipmentNo,
        /** 关联订货宝订单号orders_num。 */ String orderNo,
        /** shipped待发货、receivedin待收货、received已收货、cancelled已取消。 */ String status,
        /** 订货宝返回的状态中文名status_name。 */ String statusName,
        /** 出库类型名称，如销售出库、调拨出库。 */ String typeName,
        /** 来源客户编号client_num。 */ String customerNo,
        /** 客户名称快照client_name。 */ String customerName,
        /** 出库仓库编号stock_num。 */ String warehouseNo,
        /** 出库仓库名称stock_name。 */ String warehouseName,
        /** 发货时间ships_date，响应为UTC Instant。 */ Instant shipmentAt,
        /** 物流公司名称。 */ String logisticsName,
        /** 物流运单号express_num。 */ String trackingNo,
        /** 发货单备注。 */ String remark,
        /** 是否已经保存getShipsContent商品明细。 */ boolean detailAvailable,
        /** 最近一次成功落库时间，UTC。 */ Instant syncedAt) {
}
