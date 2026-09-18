package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.Map;

public record CustomerView(
        String sourceId, String clientGuid, String account, String companyName,
        String number, String typeSourceId, String areaSourceId, String areaGuid,
        String remark, String contactName, String email, String phone, String address,
        String staffName, String typeName, String areaName, String inviter,
        String staffSourceId, Instant createdAt, Instant updatedAt, String status,
        String clearingForm, String city, Map<String, Object> sourceFields) {
}
