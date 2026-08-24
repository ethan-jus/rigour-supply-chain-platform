package com.rigour.order.domain.enums;

/** 销售订单收款状态；由回款记录汇总维护。 */
public enum SalesOrderPaymentStatus {
    UNPAID("UNPAID", "未收款"),
    PARTIAL_PAID("PARTIAL_PAID", "部分收款"),
    PAID("PAID", "已收款");

    private final String code;
    private final String description;

    SalesOrderPaymentStatus(String code, String description) {
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
        for (SalesOrderPaymentStatus status : values()) {
            if (status.code.equals(code)) return true;
        }
        return false;
    }
}
