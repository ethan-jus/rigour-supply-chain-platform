package com.rigour.merchant.api.v1.model;

/** 外部客户/门店单行同步结果。 */
public record ExternalCrmCustomerSyncRowResult(
        String sourceCustomerId,
        Long customerId,
        String customerCode,
        String status,
        String message) {
}
