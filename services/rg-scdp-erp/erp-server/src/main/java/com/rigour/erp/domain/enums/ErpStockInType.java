package com.rigour.erp.domain.enums;

/** ERP 入库类型编码；用于统一入库单按业务来源区分。 */
public enum ErpStockInType {
    PURCHASE("PURCHASE", "采购入库"),
    TRANSFER("TRANSFER", "调拨入库"),
    RETURN("RETURN", "退货入库");

    private final String code;
    private final String description;

    ErpStockInType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}
