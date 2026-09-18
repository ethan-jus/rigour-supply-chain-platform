package com.rigour.tenant.iam.application.service.management;

import java.util.List;
import java.util.UUID;

/** SCDP 租户操作身份及受控导航契约。 */
public final class ManagementModels {
    private ManagementModels() {}

    public record Actor(String scope, UUID principalId, UUID tenantId) {
        public Actor {
            if (principalId == null
                    || !("PLATFORM".equals(scope)
                            || "TENANT".equals(scope)
                            || "SERVICE".equals(scope))) {
                throw new IllegalArgumentException("Invalid management actor");
            }
            if ("PLATFORM".equals(scope) && tenantId != null) {
                throw new IllegalArgumentException("Invalid management tenant boundary");
            }
            if (("TENANT".equals(scope) || "SERVICE".equals(scope)) && tenantId == null) {
                throw new IllegalArgumentException("Invalid management tenant boundary");
            }
        }
    }

    public record NavigationNode(
            UUID id,
            UUID parentId,
            String code,
            String type,
            String displayName,
            String permissionCode,
            String routeKey,
            String routePath,
            String componentPath,
            String iconKey,
            int sortOrder,
            boolean visible,
            boolean keepAlive,
            List<NavigationNode> children) {
        public NavigationNode {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

}
