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
        Instant updatedTime,
        Instant businessCreatedAt,
        String businessCreatedById,
        String businessCreatedByName,
        String businessCreationSource,
        String loginAccount,
        String dhbCustomerCode,
        Instant syncedAt,
        String syncedBy,
        String remark,
        String updatedBy,
        java.util.List<String> dhbCustomerCodes) {
    public InternalCustomerSummaryView(
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
        Instant updatedTime,
        Instant businessCreatedAt,
        String businessCreatedById,
        String businessCreatedByName,
        String businessCreationSource,
        String loginAccount,
        String dhbCustomerCode,
        Instant syncedAt,
        String syncedBy,
        String remark,
        String updatedBy) {
        this(id, customerCode, customerName, contactName, contactPhone, customerTypeCode, regionCode, regionName, cityName, customerSourceName, businessCategoryName, ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot, settlementTypeCode, statusCode, sourceSystemCode, sourceDocumentNo, sourceCreatedAt, sourceUpdatedAt, revision, updatedTime, businessCreatedAt, businessCreatedById, businessCreatedByName, businessCreationSource, loginAccount, dhbCustomerCode, syncedAt, syncedBy, remark, updatedBy, dhbCustomerCode == null || dhbCustomerCode.isBlank() ? java.util.List.of() : java.util.List.of(dhbCustomerCode));
    }

    public InternalCustomerSummaryView withDhbCustomerCodes(java.util.List<String> codes) {
        java.util.LinkedHashSet<String> combined = new java.util.LinkedHashSet<>();
        if (dhbCustomerCode != null && !dhbCustomerCode.isBlank()) combined.add(dhbCustomerCode);
        if (codes != null) combined.addAll(codes);
        return new InternalCustomerSummaryView(id, customerCode, customerName, contactName, contactPhone, customerTypeCode, regionCode, regionName, cityName, customerSourceName, businessCategoryName, ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot, settlementTypeCode, statusCode, sourceSystemCode, sourceDocumentNo, sourceCreatedAt, sourceUpdatedAt, revision, updatedTime, businessCreatedAt, businessCreatedById, businessCreatedByName, businessCreationSource, loginAccount, dhbCustomerCode, syncedAt, syncedBy, remark, updatedBy, java.util.List.copyOf(combined));
    }

    public InternalCustomerSummaryView(
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
        Instant updatedTime,
        Instant businessCreatedAt,
        String businessCreatedById,
        String businessCreatedByName,
        String businessCreationSource) {
        this(id, customerCode, customerName, contactName, contactPhone, customerTypeCode, regionCode, regionName, cityName, customerSourceName, businessCategoryName, ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot, settlementTypeCode, statusCode, sourceSystemCode, sourceDocumentNo, sourceCreatedAt, sourceUpdatedAt, revision, updatedTime, businessCreatedAt, businessCreatedById, businessCreatedByName, businessCreationSource, null, null, null, null, null, null);
    }


    public InternalCustomerSummaryView(
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
        this(id, customerCode, customerName, contactName, contactPhone, customerTypeCode, regionCode, regionName, cityName, customerSourceName, businessCategoryName, ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot, settlementTypeCode, statusCode, sourceSystemCode, sourceDocumentNo, sourceCreatedAt, sourceUpdatedAt, revision, updatedTime, null, null, null, null);
    }
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
