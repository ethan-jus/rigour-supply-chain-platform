package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AddressView(UUID id, String consignee, String contact, String phone,
                          String regionText, String areaName, String addressDetail,
                          String fullAddress, boolean defaultAddress,
                          Instant sourceUpdatedAt, Map<String, Object> sourceFields,
                          /** PRESENT=已见；ABSENT_CANDIDATE=待确认；ABSENT/DELETED=来源已删除。 */
                          String sourcePresence,
                          /** 首次完整快照未见的时间。 */ Instant sourceAbsentAt) {
    public AddressView {
        sourceFields = sourceFields == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sourceFields));
    }

    public AddressView(UUID id, String consignee, String contact, String phone,
                       String regionText, String areaName, String addressDetail,
                       String fullAddress, boolean defaultAddress,
                       Instant sourceUpdatedAt, Map<String, Object> sourceFields) {
        this(id, consignee, contact, phone, regionText, areaName, addressDetail, fullAddress,
                defaultAddress, sourceUpdatedAt, sourceFields, null, null);
    }
}
