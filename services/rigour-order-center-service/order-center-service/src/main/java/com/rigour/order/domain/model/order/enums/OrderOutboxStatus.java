package com.rigour.order.domain.model.order.enums;

/** 订单领域事件 Outbox 投递状态。 */
public enum OrderOutboxStatus {
    PENDING("PENDING", "待投递"),
    PUBLISHED("PUBLISHED", "已投递"),
    FAILED("FAILED", "本次投递失败，等待重试"),
    DEAD("DEAD", "超过重试上限，进入死信");

    private final String code;
    private final String description;

    OrderOutboxStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }
}
