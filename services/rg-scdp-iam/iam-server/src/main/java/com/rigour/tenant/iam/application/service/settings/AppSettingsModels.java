package com.rigour.tenant.iam.application.service.settings;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 供应链内部设置命令；不接受调用方指定租户或应用。 */
public final class AppSettingsModels {
    private AppSettingsModels() {}

    public record Context(
            boolean initialized,
            boolean canInitialize,
            String mode,
            long version,
            Set<String> permissions) {}

    public record MenuCommand(
            UUID parentId,
            String type,
            UUID resourceId,
            String name,
            String iconKey,
            int sortOrder,
            boolean visible,
            String status,
            long version,
            String routeKey,
            String routePath,
            String componentPath,
            String permissionCode) {}

    public record Impact(
            UUID id, long version, List<String> roleNames, int userCount, int childCount) {}

    public record Audit(
            UUID id,
            String actor,
            String action,
            String targetId,
            String result,
            String summary,
            Instant occurredAt) {}

    public record AuditPage(List<Audit> items, long total, int page, int pageSize) {}
}
