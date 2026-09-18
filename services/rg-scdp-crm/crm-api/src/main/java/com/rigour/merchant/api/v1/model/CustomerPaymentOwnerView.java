package com.rigour.merchant.api.v1.model;

/** 回款发生时的门店业务员证据；缺历史时不可用今天的主责补造。 */
public record CustomerPaymentOwnerView(
        String employeeCode, String employeeName, String evidence, String reason) {
    public boolean usable() {
        return employeeCode != null && !employeeCode.isBlank() && evidence != null;
    }
}
