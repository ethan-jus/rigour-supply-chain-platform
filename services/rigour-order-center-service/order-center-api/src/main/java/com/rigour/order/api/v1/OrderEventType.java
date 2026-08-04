package com.rigour.order.api.v1;

/** 订单中心对ERP、库存、客户和BI发布的版本化事件类型。 */
public enum OrderEventType {
    ORDER_IMPORTED("ORDER_IMPORTED", "外部订单首次转换为内部订单"),
    ORDER_SOURCE_UPDATED("ORDER_SOURCE_UPDATED", "外部来源订单事实发生变化");

    private final String code;
    private final String description;

    OrderEventType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }
}
