package com.rigour.merchant.api.v1.model;

/** 回款发生时的门店业务员证据；缺历史时不可用今天的主责补造。 */
public record CustomerPaymentOwnerView(
        String employeeCode, String employeeName, String evidence, String reason,
        Long departmentId, String departmentName, String regionCode) {
    public CustomerPaymentOwnerView(String code, String name, String evidence, String reason) {
        this(code,name,evidence,reason,null,null,null);
    }
    public boolean usable() {
        return employeeCode != null && !employeeCode.isBlank()
                && !java.util.Set.of("UNKNOWN", "MULTI").contains(employeeCode.trim().toUpperCase(java.util.Locale.ROOT))
                && evidence != null && !evidence.isBlank();
    }
}
