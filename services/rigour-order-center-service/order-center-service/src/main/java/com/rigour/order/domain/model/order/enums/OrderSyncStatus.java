package com.rigour.order.domain.model.order.enums;

/** 外部订单导入批次状态。 */
public enum OrderSyncStatus {
    RUNNING("RUNNING", "正在读取和转换"),
    SUCCEEDED("SUCCEEDED", "全部记录处理成功"),
    PARTIAL("PARTIAL", "部分记录成功，部分记录被拒绝"),
    FAILED("FAILED", "调用外部系统或批次处理失败");

    private final String code;
    private final String description;

    OrderSyncStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }
}
