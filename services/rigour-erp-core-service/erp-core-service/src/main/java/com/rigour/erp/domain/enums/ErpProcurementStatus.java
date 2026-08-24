package com.rigour.erp.domain.enums;

/** ERP 采购订单状态编码；用于采购订单流转和采购入库校验。 */
public enum ErpProcurementStatus {
    DRAFT("DRAFT", "草稿"),
    SUBMITTED("SUBMITTED", "已提交"),
    PARTIAL_IN("PARTIAL_IN", "部分入库"),
    COMPLETED("COMPLETED", "已完成"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String description;

    ErpProcurementStatus(String code, String description) {
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
