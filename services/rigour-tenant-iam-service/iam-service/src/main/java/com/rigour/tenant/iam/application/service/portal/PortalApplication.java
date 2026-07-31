package com.rigour.tenant.iam.application.service.portal;

import java.util.UUID;

/** 应用层已授权应用启动视图。 */
public record PortalApplication(
        UUID id, String code, String name, String iconKey, String launchMode, String targetUri, int sortOrder
) {
}
