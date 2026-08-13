package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.UUID;

public record ShippingAddressSummaryView(
        UUID id, UUID customerId, String customerCode, String customerName,
        String sourceId, String consignee, String contact, String phone,
        String regionText, String areaName, String addressDetail, String fullAddress,
        boolean defaultAddress, String status, Instant sourceUpdatedAt,
        Instant syncedAt, String sourcePresence) {
}
