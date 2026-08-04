package com.rigour.order.api.v1.model;

/**
 * 订单中心Outbox事件载荷V1。
 *
 * <p>载荷只包含跨服务消费所需的稳定标识和状态，不携带收货电话、地址、Secret或原始报文。
 * ERP、库存、客户和BI应通过orderId回调订单中心或建立自己的本地投影。</p>
 */
public record OrderImportedEvent(
        /** 平台内部订单ID。 */
        String orderId,
        /** 平台订单号。 */
        String orderNo,
        /** 来源系统编码。 */
        String sourceSystem,
        /** 来源订单号。 */
        String sourceOrderNo,
        /** 当前内部订单状态。 */
        String internalStatus,
        /** 来源订单状态原值。 */
        String sourceStatus,
        /** 来源报文版本哈希。 */
        String sourcePayloadHash) {
}
