package com.rigour.tenant.iam.application.service.settings;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 角色规则按动作保存完整条件；用户分配具体部门、地区和仓库参数。 */
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
            List<ScopeRule> rules) {}

    public record RoleCommand(
            String code,
            String name,
            String description,
            String status,
            long version,
            Set<UUID> menuNodeIds,
            List<ScopeRule> rules) {}

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
