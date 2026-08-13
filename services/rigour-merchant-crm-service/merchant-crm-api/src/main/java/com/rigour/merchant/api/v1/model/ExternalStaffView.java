package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.UUID;

public record ExternalStaffView(
        UUID id, String sourceStaffId, String accountId, String accountName,
        String staffType, String staffName, String title, String branchName,
        String accountMobile, String mobile, String email, String roleName,
        String sourceStatus, Instant sourceUpdatedAt, Instant syncedAt) {
}
