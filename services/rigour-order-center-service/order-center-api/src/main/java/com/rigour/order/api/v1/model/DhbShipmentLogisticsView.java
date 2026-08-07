package com.rigour.order.api.v1.model;

import java.time.Instant;

/** getWaitShips按订货单落库的出库/发货物流本地投影。 */
public record DhbShipmentLogisticsView(
        /** 关联订货宝订单号orders_num。 */ String orderNo,
        /** 最近一条已出库/已发货记录的单号ships_num。 */ String shipmentNo,
        /** 最近一条已出库/已发货记录的状态。 */ String status,
        /** 最近一条记录的物流公司名称。 */ String logisticsName,
        /** 最近一条记录的物流公司编码。 */ String logisticsCode,
        /** 最近一条记录的物流单号express_num。 */ String trackingNo,
        /** 最近一条记录的发货时间ships_date。 */ Instant shipmentAt,
        /** 最近一条记录的出库时间ships_time。 */ Instant stockUpAt,
        /** 最近一条记录的仓库编号stock_num。 */ String warehouseNo,
        /** 最近一条记录的仓库名称stock_name。 */ String warehouseName,
        /** 已出库/已发货记录数量。 */ int shippedCount,
        /** 待出库明细数量。 */ int waitStockCount,
        /** 最近一次成功同步时间，UTC。 */ Instant syncedAt) {
}
