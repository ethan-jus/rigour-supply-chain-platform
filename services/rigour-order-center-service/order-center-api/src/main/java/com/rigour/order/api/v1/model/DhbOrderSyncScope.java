package com.rigour.order.api.v1.model;

/** 订货宝同步本次允许访问的业务对象范围。 */
public enum DhbOrderSyncScope {
    /** 兼容历史任务：同步订单域全部已接入对象。 */
    ALL,
    /** 只同步订货单列表和订货单详情。 */
    ORDER,
    /** 只同步退货单列表和退货单详情。 */
    RETURN,
    /** 只同步出库/发货单列表和出库/发货单详情。 */
    SHIPMENT,
    /** 同步订单对应的出库/发货物流快照。 */
    SHIPMENT_LOGISTICS,
    /** 只同步收款单列表。 */
    RECEIPT,
    /** 只同步付款单列表。 */
    PAYMENT
}
