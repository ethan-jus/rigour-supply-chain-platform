package com.rigour.order.domain.enums;

import java.util.Arrays;

/** 销售退款状态；编码同步写入 business-settings 的 ORDER/SALES_REFUND_STATUS 字典。 */
public enum SalesRefundStatus {
    PENDING("PENDING"),
    CONFIRMED("CONFIRMED"),
    CANCELLED("CANCELLED");

    private final String code;

    SalesRefundStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static boolean supports(String value) {
        if (value == null || value.isBlank()) return false;
        return Arrays.stream(values()).anyMatch(status -> status.code.equals(value));
    }
}
