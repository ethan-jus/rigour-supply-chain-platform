package com.rigour.merchant.domain.enums;

/** CRM 客户状态编码；数据库只存 code，页面展示名称来自数据字典。 */
public enum CrmCustomerStatus {
    ACTIVE("ACTIVE", "启用"),
    INACTIVE("INACTIVE", "停用");

    private final String code;
    private final String description;

    CrmCustomerStatus(String code, String description) {
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
        for (CrmCustomerStatus status : values()) {
            if (status.code.equals(code)) return true;
        }
        return false;
    }
}
