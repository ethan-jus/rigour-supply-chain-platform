package com.rigour.analytics.api.v1.model;

import java.util.List;
import java.util.UUID;

/** 管理员选择已有 IAM 范围策略与业务城市；员工身份一律从源 API 核验。 */
public record BiScopeSyncCommand(UUID userId, List<UUID> iamPolicyIds, List<String> regionCodes) {
    public BiScopeSyncCommand {
        iamPolicyIds = List.copyOf(iamPolicyIds == null ? List.of() : iamPolicyIds);
        regionCodes = List.copyOf(regionCodes == null ? List.of() : regionCodes);
    }
}
