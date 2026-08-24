package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.UUID;

public record ShippingAddressSummaryView(
        UUID id, UUID customerId, String customerCode, String customerName,
        String sourceId, String consignee, String contact, String phone,
        String regionText, String areaName, String addressDetail, String fullAddress,
        boolean defaultAddress, String status, Instant sourceUpdatedAt,
        Instant syncedAt,
        /** PRESENT=已见；ABSENT_CANDIDATE=待确认；ABSENT/DELETED=来源已删除。 */
        String sourcePresence,
        /** 首次完整快照未见的时间。 */ Instant sourceAbsentAt) {
    public ShippingAddressSummaryView(
            UUID id, UUID customerId, String customerCode, String customerName,
            String sourceId, String consignee, String contact, String phone,
            String regionText, String areaName, String addressDetail, String fullAddress,
            boolean defaultAddress, String status, Instant sourceUpdatedAt,
            Instant syncedAt, String sourcePresence) {
        this(id, customerId, customerCode, customerName, sourceId, consignee, contact, phone,
                regionText, areaName, addressDetail, fullAddress, defaultAddress, status,
                sourceUpdatedAt, syncedAt, sourcePresence, null);
    }
}
