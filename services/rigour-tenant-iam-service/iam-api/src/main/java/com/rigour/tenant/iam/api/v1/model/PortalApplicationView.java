package com.rigour.tenant.iam.api.v1.model;

import java.util.UUID;

/** IAM判定后的应用卡片和启动配置；Portal不得自行根据角色拼装。 */
public record PortalApplicationView(
        UUID id,
        String code,
        String name,
        String iconKey,
        String launchMode,
        String targetUri,
        int sortOrder
) {
}
