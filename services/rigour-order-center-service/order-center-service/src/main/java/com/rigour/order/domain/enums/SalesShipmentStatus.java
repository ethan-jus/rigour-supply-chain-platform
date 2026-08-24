package com.rigour.order.domain.enums;

import java.util.Arrays;

/** 销售发货单状态；编码同步写入 business-settings 的 ORDER/SALES_SHIPMENT_STATUS 字典。 */
public enum SalesShipmentStatus {
    CREATED("CREATED"),
    SHIPPED("SHIPPED"),
    SIGNED("SIGNED"),
    CANCELLED("CANCELLED");

    private final String code;

    SalesShipmentStatus(String code) {
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
