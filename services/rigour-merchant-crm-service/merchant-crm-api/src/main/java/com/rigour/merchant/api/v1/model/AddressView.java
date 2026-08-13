package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AddressView(UUID id, String consignee, String contact, String phone,
                          String regionText, String areaName, String addressDetail,
                          String fullAddress, boolean defaultAddress,
                          Instant sourceUpdatedAt, Map<String, Object> sourceFields) {
    public AddressView {
        sourceFields = sourceFields == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sourceFields));
    }
}
