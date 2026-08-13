package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.Map;

public record StaffView(
        String sourceId, String staffId, String accountId, String staffType,
        String accountName, String staffName, String title, String branchName,
        String accountMobile, String remark, String roleName, String inviteCode,
        String mobile, String email, String qq, String status, Instant createdAt,
        Instant updatedAt, Map<String, Object> sourceFields) {
}
