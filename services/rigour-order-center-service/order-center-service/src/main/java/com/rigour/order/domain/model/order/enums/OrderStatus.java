package com.rigour.order.domain.model.order.enums;

/**
 * 内部订单状态。
 *
 * <p>这是平台自研流程状态，不等同于订货宝的 OrderStatus。订货宝状态只进入 source_status，
 * 由防腐层映射为初始内部状态；订单中心后续流程可以独立推进该状态。</p>
 */
public enum OrderStatus {
    RECEIVED("RECEIVED", "已接收外部订单，尚未进入内部流程"),
    PENDING_CONFIRMATION("PENDING_CONFIRMATION", "待确认"),
    ALLOCATING("ALLOCATING", "库存分配中"),
    SHIPPED("SHIPPED", "已发货"),
    COMPLETED("COMPLETED", "已完成"),
    CANCELLED("CANCELLED", "已取消"),
    EXCEPTION("EXCEPTION", "数据异常或无法识别来源状态");

    private final String code;
    private final String description;

    OrderStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }
}
