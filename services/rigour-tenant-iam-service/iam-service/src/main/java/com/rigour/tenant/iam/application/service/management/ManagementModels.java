package com.rigour.tenant.iam.application.service.management;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** IAM管理控制台的稳定应用层命令与只读视图。 */
public final class ManagementModels {

    private ManagementModels() {
    }

    public record Actor(String scope, UUID principalId, UUID tenantId) {
        public Actor {
            if (principalId == null || !("PLATFORM".equals(scope) || "TENANT".equals(scope))) {
                throw new IllegalArgumentException("Invalid management actor");
            }
            if ((tenantId != null) != "TENANT".equals(scope)) {
                throw new IllegalArgumentException("Invalid management tenant boundary");
            }
        }
    }

    public record ApplicationView(UUID id, String code, String name, String scope, String type,
                                  String iconKey, int sortOrder, String launchMode, String targetUri,
                                  String status, long version) {
    }

    public record ApplicationCommand(String code, String name, String scope, String type,
                                     String iconKey, int sortOrder, String launchMode, String targetUri,
                                     String status, long version) {
    }

    public record OidcClientView(UUID id, UUID applicationId, String clientId, String clientName,
                                 String redirectUri, String postLogoutRedirectUri) {
    }

    public record OidcClientCommand(UUID applicationId, String clientId, String clientName,
                                    String redirectUri, String postLogoutRedirectUri) {
    }

    public record ResourceView(UUID id, UUID applicationId, UUID parentId, String code, String type,
                               String permissionCode, String displayName, int sortOrder, String status,
                               String routeKey, String routePath, String iconKey, boolean visible,
                               boolean keepAlive, long version) {
    }

    public record ResourceCommand(UUID applicationId, UUID parentId, String code, String type,
                                  String permissionCode, String displayName, int sortOrder, String status,
                                  String routeKey, String routePath, String iconKey, boolean visible,
                                  boolean keepAlive, long version) {
    }

    public record NavigationNode(UUID id, UUID parentId, String code, String type, String displayName,
                                 String permissionCode, String routeKey, String routePath, String iconKey,
                                 int sortOrder, boolean visible, boolean keepAlive, List<NavigationNode> children) {
        public NavigationNode {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    public record TenantView(UUID id, String code, String companyName, String status,
                             long policyVersion, long version) {
    }

    public record TenantCommand(String code, String companyName, String status, long version) {
    }

    public record PackageView(UUID id, String code, String name, String description,
                              String status, long version) {
    }

    public record PackageCommand(String code, String name, String description, String status, long version) {
    }

    public record PackageVersionView(UUID id, UUID packageId, int versionNo, String publishStatus,
                                     int defaultUserLimit, String changeNote, long version,
                                     List<UUID> resourceIds) {
        public PackageVersionView {
            resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        }
    }

    public record PackageVersionCommand(int versionNo, int defaultUserLimit, String changeNote,
                                        List<UUID> resourceIds) {
        public PackageVersionCommand {
            resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        }
    }

    public record SubscriptionCommand(UUID packageVersionId, Instant effectiveFrom, Instant effectiveTo,
                                      int userLimit) {
    }

    public record SubscriptionView(UUID id, UUID tenantId, UUID packageVersionId, String packageName,
                                   int versionNo, Instant effectiveFrom, Instant effectiveTo,
                                   int userLimit, String status, long version) { }

    public record OrganizationView(UUID id, UUID parentId, String code, String name, String type,
                                   String path, int sortOrder, String status, long version) {
    }

    public record OrganizationCommand(UUID parentId, String code, String name, String type,
                                      int sortOrder, String status, long version) {
    }

    public record UserView(UUID id, String username, String displayName, String status,
                           long securityVersion, long version, List<UUID> roleIds, List<UUID> organizationIds) {
        public UserView {
            roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
            organizationIds = organizationIds == null ? List.of() : List.copyOf(organizationIds);
        }
    }

    public record UserCommand(String username, String displayName, String status,
                              String initialPassword, List<UUID> roleIds, List<UUID> organizationIds, long version) {
        public UserCommand {
            roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
            organizationIds = organizationIds == null ? List.of() : List.copyOf(organizationIds);
        }
    }

    /** 密码只用于当次强哈希，不得写入日志或审计详情。 */
    public record PasswordResetCommand(String newPassword) { }

    public record RoleView(UUID id, String code, String name, String type, String status,
                           long version, List<UUID> resourceIds) {
        public RoleView {
            resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        }
    }

    public record RoleCommand(String code, String name, String type, String status,
                              List<UUID> resourceIds, long version) {
        public RoleCommand {
            resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        }
    }

    public record DataScopeView(UUID id, UUID roleId, UUID applicationId, String scopeKey,
                                String scopeType, String status, long version) {
    }

    public record DataScopeCommand(UUID roleId, UUID applicationId, String scopeKey,
                                   String scopeType, String status, long version) {
    }

    public record SettingView(String key, String valueJson, long version) {
    }

    public record SettingCommand(String valueJson, long version) {
    }

    public record AuditView(UUID id, UUID tenantId, String actorScope, UUID actorId, String action,
                            String targetType, UUID targetId, String result, Instant occurredAt) {
    }
}
