package com.rigour.order.domain.enums;

/** 销售订单业务状态；数据库只存 code，页面展示名称来自数据字典。 */
public enum SalesOrderStatus {
    DRAFT("DRAFT", "草稿"),
    SUBMITTED("SUBMITTED", "进行中"),
    COMPLETED("COMPLETED", "已完成"),
    CANCELLED("CANCELLED", "已取消"),
    DELETED("DELETED", "已删除");

    private final String code;
    private final String description;

    SalesOrderStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static boolean supports(String code) {
        if (code == null) return false;
        for (SalesOrderStatus status : values()) {
            if (status.code.equals(code)) return true;
        }
        return false;
    }
}
