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
        String regionName,
        String cityName,
        String customerSourceName,
        String businessCategoryName,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerEmployeeCode,
        String ownerEmployeeNameSnapshot,
        String settlementTypeCode,
        String statusCode,
        String sourceSystemCode,
        String sourceDocumentNo,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        Integer revision,
        Instant updatedTime) {
    public InternalCustomerSummaryView(Long id, String customerCode, String customerName,
                                       String contactName, String contactPhone,
                                       String regionCode, String ownerSalesUserId,
                                       String ownerSalesName, String settlementTypeCode,
                                       String statusCode, Integer revision, Instant updatedTime) {
        this(id, customerCode, customerName, contactName, contactPhone, null, regionCode,
                null, null, null, null,
                ownerSalesUserId, ownerSalesName, null, null, settlementTypeCode,
                statusCode, null, null, null, null, revision, updatedTime);
    }

    public InternalCustomerSummaryView(Long id, String customerCode, String customerName,
                                       String contactName, String contactPhone, String customerTypeCode,
                                       String regionCode, String ownerSalesUserId,
                                       String ownerSalesName, String ownerEmployeeCode,
                                       String ownerEmployeeNameSnapshot, String settlementTypeCode,
                                       String statusCode, Integer revision, Instant updatedTime) {
        this(id, customerCode, customerName, contactName, contactPhone, customerTypeCode, regionCode,
                null, null, null, null,
                ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot,
                settlementTypeCode, statusCode, null, null, null, null, revision, updatedTime);
    }
}
