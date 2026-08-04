package com.rigour.order.domain.model.order.enums;

/** 外部订单来源系统编码。新增来源时扩展防腐层，不修改内部订单语义。 */
public enum OrderSourceSystem {
    DINGHUOBAO("DINGHUOBAO", "订货宝");

    private final String code;
    private final String description;

    OrderSourceSystem(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }
}
