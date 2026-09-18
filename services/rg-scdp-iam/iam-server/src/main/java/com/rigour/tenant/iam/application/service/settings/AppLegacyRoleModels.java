package com.rigour.tenant.iam.application.service.settings;

import java.util.*;

/** 迁入只复制供应链功能，旧角色及成员不变；来源和目标关系永久保留。 */
public final class AppLegacyRoleModels {
    private AppLegacyRoleModels() {}

    public record Source(
            UUID id,
            String code,
            String name,
            String status,
            long applicationVersion,
            String fingerprint,
            Set<UUID> menuNodeIds,
            List<String> permissions,
            UUID importedRoleId) {}

    public record Command(String name, long applicationVersion, String fingerprint) {}
}
