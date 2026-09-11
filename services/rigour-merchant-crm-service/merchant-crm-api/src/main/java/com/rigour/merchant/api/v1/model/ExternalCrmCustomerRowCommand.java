package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 外部客户/门店单行同步命令。 */
public record ExternalCrmCustomerRowCommand(
        UUID connectorId,
        String sourceTenantKey,
        String sourceCustomerId,
        String sourceDocumentNo,
        String customerName,
        String contactName,
        String contactPhone,
        String customerSourceName,
        String businessCategoryName,
        String regionName,
        String cityName,
        String address,
        String ownerEmployeeCode,
        String ownerEmployeeNameSnapshot,
        String settlementTypeCode,
        String statusName,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        String sourcePayloadHash,
        String sourcePayloadJson) {
}
