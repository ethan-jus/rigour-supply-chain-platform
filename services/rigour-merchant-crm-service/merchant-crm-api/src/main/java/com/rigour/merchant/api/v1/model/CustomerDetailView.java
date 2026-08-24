package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CustomerDetailView(
        UUID id, String code, String name, String internalStatus, String account,
        String typeName, String areaName, String city, String inviter, String remark,
        String contactName, String phone, String email, String address,
        String settlementMode, String staffName, List<SalesAssignmentView> salesAssignments,
        String sourceStatus,
        Instant sourceCreatedAt, Instant sourceUpdatedAt, Instant syncedAt,
        /** PRESENT=已见；ABSENT_CANDIDATE=待确认；ABSENT/DELETED=来源已删除。 */
        String sourcePresence,
        /** 首次完整快照未见的时间。 */ Instant sourceAbsentAt,
        List<AddressView> shippingAddresses,
        Map<String, Object> sourceFields, CustomerSourceView source) {
    public CustomerDetailView {
        shippingAddresses = shippingAddresses == null ? List.of() : List.copyOf(shippingAddresses);
        salesAssignments = salesAssignments == null ? List.of() : List.copyOf(salesAssignments);
        sourceFields = sourceFields == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sourceFields));
        source = source == null ? new CustomerSourceView(null, null, null, null, null, null) : source;
    }

    /** 兼容未识别来源缺失时间的旧调用方。 */
    public CustomerDetailView(
            UUID id, String code, String name, String internalStatus, String account,
            String typeName, String areaName, String city, String inviter, String remark,
            String contactName, String phone, String email, String address,
            String settlementMode, String staffName, List<SalesAssignmentView> salesAssignments,
            String sourceStatus, Instant sourceCreatedAt, Instant sourceUpdatedAt, Instant syncedAt,
            String sourcePresence, List<AddressView> shippingAddresses,
            Map<String, Object> sourceFields, CustomerSourceView source) {
        this(id, code, name, internalStatus, account, typeName, areaName, city, inviter, remark,
                contactName, phone, email, address, settlementMode, staffName, salesAssignments,
                sourceStatus, sourceCreatedAt, sourceUpdatedAt, syncedAt, sourcePresence, null,
                shippingAddresses, sourceFields, source);
    }
}
