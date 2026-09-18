package com.rigour.erp.domain.enums;

/** ERP 调拨单状态编码；数据库只存 code，页面展示名称来自数据字典。 */
public enum ErpTransferStatus {
    DRAFT("DRAFT", "草稿"),
    OUT_CONFIRMED("OUT_CONFIRMED", "已确认出库"),
    IN_CONFIRMED("IN_CONFIRMED", "已确认入库"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String description;

    ErpTransferStatus(String code, String description) {
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
