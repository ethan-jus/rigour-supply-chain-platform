package com.rigour.order.api.v1.model;

/** 订货宝发货信息的规范化投影。 */
public record DhbShipmentView(
        /** 来源发货单号。 */
        String shipmentNo,
        /** 来源发货状态。 */
        String status,
        /** 来源发货时间。 */
        String shipmentDate,
        /** 来源备货时间。 */
        String stockUpTime) {
}
