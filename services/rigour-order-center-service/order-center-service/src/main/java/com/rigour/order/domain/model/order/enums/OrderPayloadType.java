package com.rigour.order.domain.model.order.enums;

/** 订货宝原始报文类型；LIST 是订单列表摘要，DETAIL 是订单明细回执。 */
public enum OrderPayloadType {
    LIST("LIST", "getOrderList 返回的订单摘要"),
    DETAIL("DETAIL", "getOrderContent 返回的订单明细");

    private final String code;
    private final String description;

    OrderPayloadType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }
}
