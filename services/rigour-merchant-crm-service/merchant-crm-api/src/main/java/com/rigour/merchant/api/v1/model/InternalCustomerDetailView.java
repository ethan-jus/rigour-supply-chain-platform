package com.rigour.merchant.api.v1.model;

import java.time.Instant;

/** CRM 自研客户详情视图；详情返回编辑和审计需要的完整业务字段。 */
public record InternalCustomerDetailView(
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
        String address,
        String statusCode,
        String remark,
        String sourceSystemCode,
        String sourceDocumentNo,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public InternalCustomerDetailView(Long id, String customerCode, String customerName,
                                      String contactName, String contactPhone,
                                      String regionCode, String ownerSalesUserId,
                                      String ownerSalesName, String settlementTypeCode,
                                      String address, String statusCode, String remark,
                                      Integer revision, String createdBy, Instant createdTime,
                                      String updatedBy, Instant updatedTime) {
        this(id, customerCode, customerName, contactName, contactPhone, null, regionCode,
                null, null, null, null,
                ownerSalesUserId, ownerSalesName, null, null, settlementTypeCode,
                address, statusCode, remark, null, null, null, null,
                revision, createdBy, createdTime, updatedBy, updatedTime);
    }

    public InternalCustomerDetailView(Long id, String customerCode, String customerName,
                                      String contactName, String contactPhone, String customerTypeCode,
                                      String regionCode, String ownerSalesUserId,
                                      String ownerSalesName, String ownerEmployeeCode,
                                      String ownerEmployeeNameSnapshot, String settlementTypeCode,
                                      String address, String statusCode, String remark,
                                      Integer revision, String createdBy, Instant createdTime,
                                      String updatedBy, Instant updatedTime) {
        this(id, customerCode, customerName, contactName, contactPhone, customerTypeCode, regionCode,
                null, null, null, null,
                ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot,
                settlementTypeCode, address, statusCode, remark, null, null, null, null,
                revision, createdBy, createdTime, updatedBy, updatedTime);
    }
}
