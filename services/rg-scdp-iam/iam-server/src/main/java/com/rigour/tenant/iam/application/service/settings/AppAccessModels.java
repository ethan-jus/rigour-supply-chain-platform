package com.rigour.tenant.iam.application.service.settings;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 角色统一数据范围；动作条件由后端根据菜单权限生成。 */
public final class AppAccessModels {
    private AppAccessModels() {}

    public record ScopeRule(
            UUID id,
            String actionCode,
            String objectType,
            String scopeMode,
            String departmentMode,
            String regionMode,
            String warehouseMode,
            boolean includeDescendants,
            Map<String, List<String>> references) {}

    public record RoleDataScope(String mode, List<String> departmentIds) {}

    public record Role(
            UUID id,
            String code,
            String name,
            String description,
            String status,
            boolean protectedRole,
            long version,
            int userCount,
            Set<UUID> menuNodeIds,
            List<ScopeRule> rules,
            RoleDataScope dataScope) {
        public Role(
                UUID id,
                String code,
                String name,
                String description,
                String status,
                boolean protectedRole,
                long version,
                int userCount,
                Set<UUID> menuNodeIds,
                List<ScopeRule> rules) {
            this(
                    id,
                    code,
                    name,
                    description,
                    status,
                    protectedRole,
                    version,
                    userCount,
                    menuNodeIds,
                    rules,
                    null);
        }
    }

    public record RoleCommand(
            String code,
            String name,
            String description,
            String status,
            long version,
            Set<UUID> menuNodeIds,
            List<ScopeRule> rules,
            RoleDataScope dataScope) {}

    public record RoleImpact(
            UUID id,
            long version,
            int userCount,
            List<String> usernames,
            String status,
            int activeUserCount,
            List<String> lastRoleUsernames,
            List<String> managementEntryUsernames,
            boolean canDisable,
            boolean canDelete) {}
}
