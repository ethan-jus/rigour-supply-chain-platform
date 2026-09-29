package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.tenant.iam.application.port.out.AppEmployeeClient;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.*;
import com.rigour.tenant.iam.application.service.settings.AppGrantDeniedException;
import com.rigour.tenant.iam.application.service.settings.RoleDataScopes;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

class AppRoleDelegationTest {
    private final Actor actor = new Actor("TENANT", UUID.randomUUID(), UUID.randomUUID());
    private final UUID roleId = UUID.randomUUID();
    private final Node read = node("hr:position:read"), write = node("hr:position:write");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcAppSettingsStore settings = mock(JdbcAppSettingsStore.class);
    private JdbcAppRoleStore roles;

    @BeforeEach
    void setUp() {
        roles = spy(new JdbcAppRoleStore(jdbc, settings, mock(PlatformTransactionManager.class),
                mock(AppReferenceValidator.class), mock(AppEmployeeClient.class)));
        when(settings.rows(actor)).thenReturn(List.of(read, write));
        when(settings.applicationId()).thenReturn(UUID.randomUUID());
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(), any(), any(), any()))
                .thenReturn(List.of(roleId));
    }

    @Test
    void managementButtonsDoNotPermitDelegatingMissingBusinessFunctions() {
        when(settings.permissions(actor)).thenReturn(Set.of("supply:role:grant", "supply:user:assign-role"));
        assertThatThrownBy(() -> roles.requireDelegable(actor, Set.of(read.id(), write.id()), List.of()))
                .isInstanceOfSatisfying(AppGrantDeniedException.class, denied ->
                        assertThat(denied.missingPermissions()).containsExactly("hr:position:read", "hr:position:write"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void ownedFunctionsStillRequireAnOwnedDataScopeAndAllowCoveredNarrowerScope() {
        when(settings.permissions(actor)).thenReturn(Set.of(read.permissionCode()));
        var all = RoleDataScopes.rule(new RoleDataScope("ALL", List.of()), read.permissionCode());
        var own = RoleDataScopes.rule(new RoleDataScope("SELF", List.of()), read.permissionCode());
        doReturn(List.of(role(own))).when(roles).all(actor);
        assertThatThrownBy(() -> roles.requireDelegable(actor, Set.of(read.id()), List.of(all)))
                .isInstanceOfSatisfying(AppGrantDeniedException.class, denied -> {
                    assertThat(denied.missingPermissions()).isEmpty();
                    assertThat(denied.getMessage()).contains("数据范围");
                });
        doReturn(List.of(role(all))).when(roles).all(actor);
        assertThatCode(() -> roles.requireDelegable(actor, Set.of(read.id()), List.of(own)))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownMenuRemainsGenericForbiddenAndProtectedAdministratorBehaviorIsUnchanged() {
        assertThatThrownBy(() -> roles.requireDelegable(actor, Set.of(UUID.randomUUID()), List.of()))
                .isExactlyInstanceOf(AccessDeniedException.class);
        when(settings.protectedAdministrator(actor)).thenReturn(true);
        assertThatCode(() -> roles.requireDelegable(actor, Set.of(read.id()), List.of()))
                .doesNotThrowAnyException();
    }

    private Role role(ScopeRule rule) {
        return new Role(roleId, "SYS_ADMIN", "系统管理员", null, "ACTIVE", false, 0, 1,
                Set.of(read.id()), List.of(rule));
    }

    private static Node node(String permission) {
        return new Node(UUID.randomUUID(), null, "BUTTON", UUID.randomUUID(), permission,
                null, 0, true, "ACTIVE", false, 0, permission, permission, null, null, null);
    }
}
