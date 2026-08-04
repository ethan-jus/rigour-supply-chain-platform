package com.rigour.order.api.v1.model;

import java.util.List;

/** 订单明细本地投影；外部补拉由Integration同步任务负责。 */
public record DhbOrderDetailView(
        /** 订单主信息。 */
        DhbOrderView order,
        /** 订单商品明细。 */
        List<DhbOrderLineView> lines,
        /** 订单发货信息。 */
        List<DhbShipmentView> shipments,
        /** 本次响应是否经过外部接口刷新；本地查询接口固定为false。 */
        boolean synchronizedFromProvider) {
}
