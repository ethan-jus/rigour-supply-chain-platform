package com.rigour.erp.domain.enums;

/** ERP 出库类型编码；统一出库单通过该编码区分业务来源。 */
public enum ErpStockOutType {
    SALES("SALES", "销售出库"),
    TRANSFER("TRANSFER", "调拨出库"),
    PURCHASE_RETURN("PURCHASE_RETURN", "采购退货出库"),
    INVENTORY_LOSS("INVENTORY_LOSS", "盘亏出库"),
    OTHER("OTHER", "其他出库"),
    JOINT_OPERATION("JOINT_OPERATION", "联营出库");

    private final String code;
    private final String description;

    ErpStockOutType(String code, String description) {
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
