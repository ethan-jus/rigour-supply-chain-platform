package com.rigour.merchant.api.v1.model;

import java.time.Instant;

/** CRM 自研客户列表视图；列表只返回业务识别和筛选展示需要的字段。 */
public record InternalCustomerSummaryView(
        Long id,
        String customerCode,
        String customerName,
        String contactName,
        String contactPhone,
        String customerTypeCode,
        String regionCode,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerStaffCode,
        String ownerStaffNameSnapshot,
        String settlementTypeCode,
        String statusCode,
        Integer revision,
        Instant updatedTime) {
    public InternalCustomerSummaryView(Long id, String customerCode, String customerName,
                                       String contactName, String contactPhone,
                                       String regionCode, String ownerSalesUserId,
                                       String ownerSalesName, String settlementTypeCode,
                                       String statusCode, Integer revision, Instant updatedTime) {
        this(id, customerCode, customerName, contactName, contactPhone, null, regionCode,
                ownerSalesUserId, ownerSalesName, null, null, settlementTypeCode,
                statusCode, revision, updatedTime);
    }
}
