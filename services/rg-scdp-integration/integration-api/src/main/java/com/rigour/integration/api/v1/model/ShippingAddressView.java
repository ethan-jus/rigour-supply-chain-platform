package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.Map;

public record ShippingAddressView(
        String sourceId, String addressId, String addressGuid, String clientId,
        String clientGuid, String clientNumber, String consignee, String contact,
        String phone, String regionText, Boolean defaultAddress, Instant updatedAt,
        String addressDetail, String areaName, Map<String, Object> sourceFields) {
}
