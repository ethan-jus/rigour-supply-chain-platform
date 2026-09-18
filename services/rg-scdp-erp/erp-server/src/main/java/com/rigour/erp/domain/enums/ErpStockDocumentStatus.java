package com.rigour.erp.domain.enums;

/** ERP 出入库单状态编码；出库单和入库单共用这组状态。 */
public enum ErpStockDocumentStatus {
    DRAFT("DRAFT", "草稿"),
    CONFIRMED("CONFIRMED", "已确认"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String description;

    ErpStockDocumentStatus(String code, String description) {
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
