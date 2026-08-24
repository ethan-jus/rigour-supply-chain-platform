package com.rigour.tenant.iam.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.tenant.iam.application.port.out.IamRolePermissionStore;
import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.RolePermissionModels.*;
import com.rigour.tenant.iam.domain.code.IamBusinessCodeRules;
import com.rigour.tenant.iam.infrastructure.persistence.dataobject.RoleDO;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.RoleMapper;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.RolePermissionMapper;
import com.rigour.tenant.iam.infrastructure.persistence.projection.GrantableResourceRow;
import com.rigour.tenant.iam.infrastructure.persistence.projection.RoleResourceGrantRow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 角色权限MyBatis-Plus持久化实现；授权只能写入当前租户套餐内的我方资源。 */
@Repository
public class MybatisPlusIamRolePermissionStore implements IamRolePermissionStore {
    private static final Logger log = LoggerFactory.getLogger(MybatisPlusIamRolePermissionStore.class);

    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final String CUSTOM = "CUSTOM";
    private static final Set<String> ROLE_STATUSES = Set.of(ACTIVE, DISABLED);

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final IdentifierGenerator ids;
    private final BusinessCodeGenerator codeGenerator;

    public MybatisPlusIamRolePermissionStore(RoleMapper roleMapper,
                                             RolePermissionMapper rolePermissionMapper,
                                             IdentifierGenerator ids) {
        this.roleMapper = Objects.requireNonNull(roleMapper, "roleMapper");
        this.rolePermissionMapper = Objects.requireNonNull(rolePermissionMapper, "rolePermissionMapper");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.codeGenerator = new BusinessCodeGenerator();
    }

    @Override
    public List<RolePermissionView> roles(Actor actor) {
        requireTenantPermission(actor, "iam:role:read");
        Map<UUID, List<UUID>> grants = activeResourceIdsByRole(actor.tenantId());
        return roleMapper.selectList(Wrappers.<RoleDO>lambdaQuery()
                        .eq(RoleDO::getTenantId, actor.tenantId())
                        .isNull(RoleDO::getDeletedAt)
                        .orderByDesc(RoleDO::getRoleType)
                        .orderByAsc(RoleDO::getRoleCode))
                .stream()
                .map(role -> roleView(role, grants.getOrDefault(role.getId(), List.of())))
                .toList();
    }

    @Override
    public List<GrantableResourceView> grantableResources(Actor actor) {
        requireTenantPermission(actor, "iam:role:read");
        return visibleGrantableRows(actor.tenantId()).stream()
                .map(MybatisPlusIamRolePermissionStore::resourceView)
                .toList();
    }

    @Override
    @Transactional
    public RolePermissionView createRole(Actor actor, RolePermissionCommand command) {
        requireTenantPermission(actor, "iam:role:write");
        requireTenantPermission(actor, "iam:role:grant");
        RolePermissionCommand normalized = normalize(command, false);
        List<UUID> resourceIds = normalizedResourceIds(normalized.resourceIds());
        assertGrantableResources(actor.tenantId(), resourceIds);

        LocalDateTime now = now();
        RoleDO role = new RoleDO();
        role.setId(ids.nextId());
        role.setTenantId(actor.tenantId());
        role.setRoleCode(nextRoleCode(actor.tenantId()));
        role.setRoleName(normalized.name());
        role.setDescription(normalized.description());
        role.setRoleType(CUSTOM);
        role.setStatus(normalized.status());
        role.setVersion(0);
        role.setCreatedAt(now);
        role.setCreatedBy(actor.principalId());
        role.setUpdatedAt(now);
        role.setUpdatedBy(actor.principalId());
        try {
            roleMapper.insert(role);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("角色编码生成冲突，请重试", exception);
        }
        assignRoleResources(actor, role.getId(), resourceIds);
        rolePermissionMapper.bumpTenantPolicy(actor.tenantId());
        audit(actor, "ROLE_PERMISSION_CREATE", "ROLE", role.getId());
        log.info("IAM角色创建完成 tenantId={} roleId={} roleCode={} roleName={} grantCount={} actorId={}",
                actor.tenantId(), role.getId(), role.getRoleCode(), role.getRoleName(),
                resourceIds.size(), actor.principalId());
        return roleById(actor.tenantId(), role.getId());
    }

    @Override
    @Transactional
    public RolePermissionView updateRole(Actor actor, UUID id, RolePermissionCommand command) {
        requireTenantPermission(actor, "iam:role:write");
        requireTenantPermission(actor, "iam:role:grant");
        if (id == null) throw new IllegalArgumentException("role id is required");
        requireCustomRole(actor.tenantId(), id);
        RolePermissionCommand normalized = normalize(command, true);
        List<UUID> resourceIds = normalizedResourceIds(normalized.resourceIds());
        assertGrantableResources(actor.tenantId(), resourceIds);

        RoleDO update = new RoleDO();
        update.setRoleName(normalized.name());
        update.setDescription(normalized.description());
        update.setStatus(normalized.status());
        update.setVersion(normalized.version() + 1);
        update.setUpdatedAt(now());
        update.setUpdatedBy(actor.principalId());
        int updated = roleMapper.update(update, Wrappers.<RoleDO>lambdaUpdate()
                .eq(RoleDO::getTenantId, actor.tenantId())
                .eq(RoleDO::getId, id)
                .eq(RoleDO::getRoleType, CUSTOM)
                .eq(RoleDO::getVersion, normalized.version())
                .isNull(RoleDO::getDeletedAt));
        if (updated != 1) throw new IllegalArgumentException("角色不存在或版本已变化");
        assignRoleResources(actor, id, resourceIds);
        rolePermissionMapper.bumpTenantPolicy(actor.tenantId());
        audit(actor, "ROLE_PERMISSION_UPDATE", "ROLE", id);
        RolePermissionView result = roleById(actor.tenantId(), id);
        log.info("IAM角色授权修改完成 tenantId={} roleId={} roleCode={} roleName={} grantCount={} version={} actorId={}",
                actor.tenantId(), result.id(), result.code(), result.name(), resourceIds.size(),
                result.version(), actor.principalId());
        return result;
    }

    private RolePermissionView roleById(UUID tenantId, UUID id) {
        RoleDO role = roleMapper.selectOne(Wrappers.<RoleDO>lambdaQuery()
                .eq(RoleDO::getTenantId, tenantId)
                .eq(RoleDO::getId, id)
                .isNull(RoleDO::getDeletedAt)
                .last("LIMIT 1"));
        if (role == null) throw new AccessDeniedException("角色不属于当前租户");
        return roleView(role, activeResourceIdsByRole(tenantId).getOrDefault(id, List.of()));
    }

    private void assignRoleResources(Actor actor, UUID roleId, List<UUID> resourceIds) {
        rolePermissionMapper.inactivateRoleResources(actor.tenantId(), roleId, actor.principalId());
        for (UUID resourceId : resourceIds) {
            rolePermissionMapper.upsertRoleResource(actor.tenantId(), roleId, resourceId, actor.principalId());
        }
    }

    private void requireTenantPermission(Actor actor, String permission) {
        if (!"TENANT".equals(actor.scope())
                || rolePermissionMapper.countTenantPermission(actor.tenantId(), actor.principalId(), permission) < 1) {
            throw new AccessDeniedException("Permission denied: " + permission);
        }
    }

    private void requireCustomRole(UUID tenantId, UUID roleId) {
        RoleDO role = roleMapper.selectOne(Wrappers.<RoleDO>lambdaQuery()
                .eq(RoleDO::getTenantId, tenantId)
                .eq(RoleDO::getId, roleId)
                .isNull(RoleDO::getDeletedAt)
                .last("LIMIT 1"));
        if (role == null) throw new AccessDeniedException("角色不属于当前租户");
        if (!CUSTOM.equals(role.getRoleType())) {
            throw new AccessDeniedException("系统角色由IAM维护，不能在角色权限页面编辑");
        }
    }

    private void assertGrantableResources(UUID tenantId, List<UUID> resourceIds) {
        Set<UUID> grantableIds = new LinkedHashSet<>();
        visibleGrantableRows(tenantId).forEach(resource -> grantableIds.add(resource.getId()));
        for (UUID resourceId : resourceIds) {
            if (!grantableIds.contains(resourceId)) {
                throw new AccessDeniedException("资源不在当前租户可授权范围内");
            }
        }
    }

    private List<GrantableResourceRow> visibleGrantableRows(UUID tenantId) {
        List<GrantableResourceRow> rows = rolePermissionMapper.selectEntitledResources(tenantId);
        Map<UUID, GrantableResourceRow> byId = new LinkedHashMap<>();
        for (GrantableResourceRow row : rows) {
            byId.put(row.getId(), row);
        }
        return rows.stream().filter(resource -> grantableThroughVisibleMenu(resource, byId)).toList();
    }

    private Map<UUID, List<UUID>> activeResourceIdsByRole(UUID tenantId) {
        Map<UUID, List<UUID>> result = new HashMap<>();
        for (RoleResourceGrantRow grant : rolePermissionMapper.selectActiveRoleResources(tenantId)) {
            result.computeIfAbsent(grant.getRoleId(), ignored -> new ArrayList<>()).add(grant.getResourceId());
        }
        return result;
    }

    private boolean grantableThroughVisibleMenu(GrantableResourceRow resource, Map<UUID, GrantableResourceRow> byId) {
        GrantableResourceRow current = resource;
        Set<UUID> visited = new LinkedHashSet<>();
        while (current != null && visited.add(current.getId())) {
            if (("MENU".equals(current.getType()) || "PAGE".equals(current.getType())) && !current.isVisible()) {
                return false;
            }
            current = byId.get(current.getParentId());
        }
        return true;
    }

    private String nextRoleCode(UUID tenantId) {
        return codeGenerator.generateUnique(IamBusinessCodeRules.ROLE,
                candidate -> roleMapper.selectCount(Wrappers.<RoleDO>lambdaQuery()
                        .eq(RoleDO::getTenantId, tenantId)
                        .eq(RoleDO::getRoleCode, candidate)) == 0);
    }

    private void audit(Actor actor, String action, String targetType, UUID targetId) {
        rolePermissionMapper.insertAudit(ids.nextId(), actor.tenantId(), actor.scope(), actor.principalId(),
                action, targetType, targetId, ids.nextId());
    }

    private static RolePermissionCommand normalize(RolePermissionCommand command, boolean update) {
        if (command == null) throw new IllegalArgumentException("角色参数不能为空");
        if (update && command.version() < 0) throw new IllegalArgumentException("角色版本无效");
        return new RolePermissionCommand(
                required(command.name(), 128, "roleName"),
                text(command.description(), 500, "description"),
                enumValue(command.status(), ROLE_STATUSES, ACTIVE, "status"),
                command.resourceIds(),
                command.version());
    }

    private static List<UUID> normalizedResourceIds(List<UUID> resourceIds) {
        LinkedHashSet<UUID> normalized = new LinkedHashSet<>();
        for (UUID resourceId : resourceIds == null ? List.<UUID>of() : resourceIds) {
            if (resourceId == null) throw new IllegalArgumentException("资源ID不能为空");
            normalized.add(resourceId);
        }
        return List.copyOf(normalized);
    }

    private static RolePermissionView roleView(RoleDO role, List<UUID> resourceIds) {
        return new RolePermissionView(role.getId(), role.getRoleCode(), role.getRoleName(), role.getDescription(),
                role.getRoleType(), role.getStatus(), role.getVersion(), toInstant(role.getUpdatedAt()), resourceIds);
    }

    private static GrantableResourceView resourceView(GrantableResourceRow row) {
        return new GrantableResourceView(row.getId(), row.getApplicationId(), row.getApplicationCode(),
                row.getApplicationName(), row.getParentId(), row.getCode(), row.getType(),
                row.getPermissionCode(), row.getDisplayName(), row.getSortOrder(), row.getStatus(),
                row.getRouteKey(), row.getRoutePath(), row.getIconKey(), row.isVisible(), row.isKeepAlive());
    }

    private static String enumValue(String value, Set<String> allowed, String defaultValue, String field) {
        String normalized = value == null || value.isBlank()
                ? defaultValue
                : value.strip().toUpperCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }

    private static String required(String value, int max, String field) {
        String normalized = text(value, max, field);
        if (normalized == null) throw new IllegalArgumentException(field + "不能为空");
        return normalized;
    }

    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(field + "长度不能超过" + max);
        }
        return normalized;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
