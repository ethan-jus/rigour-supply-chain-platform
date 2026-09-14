package com.rigour.tenant.iam.infrastructure.persistence.repository;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.RolePermissionModels.RolePermissionCommand;
import com.rigour.tenant.iam.infrastructure.persistence.dataobject.RoleDO;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.RoleMapper;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.RolePermissionMapper;
import com.rigour.tenant.iam.infrastructure.persistence.projection.GrantableResourceRow;
import com.rigour.tenant.iam.infrastructure.persistence.projection.RoleResourceGrantRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证页面权限模式来自可信系统角色，并保持普通角色显式授权与套餐边界。 */
@ExtendWith(MockitoExtension.class)
class MybatisPlusIamRolePermissionStoreTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID READ = UUID.randomUUID();
    private static final UUID WRITE = UUID.randomUUID();
    @Mock private RoleMapper roleMapper;
    @Mock private RolePermissionMapper permissionMapper;
    private MybatisPlusIamRolePermissionStore store;

    @BeforeEach
    void setUp() {
        IdentifierGenerator ids = UUID::randomUUID;
        store = new MybatisPlusIamRolePermissionStore(roleMapper, permissionMapper, ids);
    }

    @Test
    void roleViewReportsAutomaticModeWithEntireCurrentlyGrantableCatalog() {
        when(permissionMapper.countTenantPermission(TENANT, USER, "iam:role:read")).thenReturn(1);
        when(roleMapper.selectList(any())).thenReturn(List.of(role(ADMIN, "TENANT_SUPER_ADMIN", "SYSTEM"), role(SALES, "SALES_CUSTOM", "CUSTOM")));
        when(permissionMapper.selectActiveRoleResources(TENANT)).thenReturn(List.of(grant(SALES, READ)));
        when(permissionMapper.selectEntitledResources(TENANT)).thenReturn(List.of(resource(READ), resource(WRITE)));
        var roles = store.roles(actor());
        assertThat(roles.getFirst().permissionMode()).isEqualTo("ALL_ENTITLED");
        assertThat(roles.getFirst().resourceIds()).containsExactly(READ, WRITE);
        assertThat(roles.getLast().permissionMode()).isEqualTo("EXPLICIT");
        assertThat(roles.getLast().resourceIds()).containsExactly(READ);
        verify(permissionMapper, never()).upsertRoleResource(any(), any(), any(), any());
    }

    @Test
    void customRoleCannotSelectAutomaticModeThroughAdministratorCode() {
        when(permissionMapper.countTenantPermission(TENANT, USER, "iam:role:read")).thenReturn(1);
        when(roleMapper.selectList(any())).thenReturn(List.of(role(SALES, "TENANT_SUPER_ADMIN", "CUSTOM")));
        when(permissionMapper.selectActiveRoleResources(TENANT)).thenReturn(List.of(grant(SALES, READ)));
        when(permissionMapper.selectEntitledResources(TENANT)).thenReturn(List.of(resource(READ), resource(WRITE)));
        var role = store.roles(actor()).getFirst();
        assertThat(role.permissionMode()).isEqualTo("EXPLICIT");
        assertThat(role.resourceIds()).containsExactly(READ);
    }

    @Test
    void systemAdministratorPermissionEditsAreRejectedServerSide() {
        allowWrite();
        when(roleMapper.selectOne(any())).thenReturn(role(ADMIN, "TENANT_SUPER_ADMIN", "SYSTEM"));
        assertThatThrownBy(() -> store.updateRole(actor(), ADMIN, new RolePermissionCommand("admin", null, "ACTIVE", List.of(), 0)))
                .isInstanceOf(AccessDeniedException.class);
        verify(permissionMapper, never()).inactivateRoleResources(any(), any(), any());
        verify(permissionMapper, never()).upsertRoleResource(any(), any(), any(), any());
    }

    @Test
    void ordinaryRoleCannotAddResourceOutsideTenantEntitlements() {
        allowWrite();
        when(roleMapper.selectOne(any())).thenReturn(role(SALES, "SALES_CUSTOM", "CUSTOM"));
        when(permissionMapper.selectEntitledResources(TENANT)).thenReturn(List.of(resource(READ)));
        assertThatThrownBy(() -> store.updateRole(actor(), SALES, new RolePermissionCommand("sales", null, "ACTIVE", List.of(READ, WRITE), 0)))
                .isInstanceOf(AccessDeniedException.class);
        verify(permissionMapper, never()).inactivateRoleResources(any(), any(), any());
    }

    @Test
    void ordinaryRolePersistsOnlyExplicitSelection() {
        allowWrite();
        when(roleMapper.selectOne(any())).thenReturn(role(SALES, "SALES_CUSTOM", "CUSTOM"));
        when(roleMapper.update(any(RoleDO.class), any())).thenReturn(1);
        when(permissionMapper.selectEntitledResources(TENANT)).thenReturn(List.of(resource(READ), resource(WRITE)));
        when(permissionMapper.selectActiveRoleResources(TENANT)).thenReturn(List.of(grant(SALES, WRITE)));
        var result = store.updateRole(actor(), SALES, new RolePermissionCommand("sales", null, "ACTIVE", List.of(WRITE), 0));
        assertThat(result.permissionMode()).isEqualTo("EXPLICIT");
        assertThat(result.resourceIds()).containsExactly(WRITE);
        verify(permissionMapper).inactivateRoleResources(TENANT, SALES, USER);
        verify(permissionMapper).upsertRoleResource(TENANT, SALES, WRITE, USER);
        verify(permissionMapper, never()).upsertRoleResource(any(), any(), eq(READ), any());
    }

    private void allowWrite() {
        when(permissionMapper.countTenantPermission(TENANT, USER, "iam:role:write")).thenReturn(1);
        when(permissionMapper.countTenantPermission(TENANT, USER, "iam:role:grant")).thenReturn(1);
    }
    private Actor actor() { return new Actor("TENANT", USER, TENANT); }
    private static RoleDO role(UUID id, String code, String type) {
        RoleDO role = new RoleDO();
        role.setId(id); role.setTenantId(TENANT); role.setRoleCode(code); role.setRoleType(type); role.setStatus("ACTIVE");
        return role;
    }
    private static GrantableResourceRow resource(UUID id) {
        GrantableResourceRow row = new GrantableResourceRow();
        row.setId(id); row.setType("API"); row.setVisible(true);
        return row;
    }
    private static RoleResourceGrantRow grant(UUID role, UUID resource) {
        RoleResourceGrantRow row = new RoleResourceGrantRow();
        row.setRoleId(role); row.setResourceId(resource);
        return row;
    }
}
