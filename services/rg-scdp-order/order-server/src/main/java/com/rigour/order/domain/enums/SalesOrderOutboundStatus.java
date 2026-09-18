package com.rigour.order.domain.enums;

/** 销售订单出库状态；销售出库单确认后回写订单。 */
public enum SalesOrderOutboundStatus {
    PENDING("PENDING", "待出库"),
    PARTIAL_OUT("PARTIAL_OUT", "部分出库"),
    OUT_CONFIRMED("OUT_CONFIRMED", "已出库");

    private final String code;
    private final String description;

    SalesOrderOutboundStatus(String code, String description) {
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
        for (SalesOrderOutboundStatus status : values()) {
            if (status.code.equals(code)) return true;
        }
        return false;
    }
}
