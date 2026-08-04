package com.rigour.order.domain.model.order;

/** 平台内部订单发货信息模型。 */
public record OrderShipment(
        /** 平台内部发货记录ID。 */
        String id,
        /** 来源发货单号。 */
        String sourceShipmentNo,
        /** 来源发货状态原值。 */
        String status,
        /** 来源发货时间原值。 */
        String shipmentDate,
        /** 来源备货时间原值。 */
        String stockUpTime) {
}
