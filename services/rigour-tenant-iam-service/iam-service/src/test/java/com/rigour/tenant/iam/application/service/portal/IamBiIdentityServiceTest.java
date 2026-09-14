package com.rigour.tenant.iam.application.service.portal;

import com.rigour.tenant.iam.application.model.IamBiIdentity;
import com.rigour.tenant.iam.application.port.out.IamBiIdentityStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 当前 JWT 账号只能验证本人，跨账号读取沿用 IAM 已有权限。 */
class IamBiIdentityServiceTest {
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private final PortalAccessService access = mock(PortalAccessService.class);
    private final IamBiIdentityStore store = mock(IamBiIdentityStore.class);
    private final IamBiIdentityService service = new IamBiIdentityService(access, store);
    private final PortalAccessQuery actor = new PortalAccessQuery("TENANT", USER, TENANT);

    @Test void ordinaryUserCanVerifyOwnExactBindingWithoutDirectoryReadPermission() {
        when(access.currentUser(actor)).thenReturn(current(USER, Set.of(), Set.of("analytics:dashboard:read")));
        var expected = new IamBiIdentity(USER, "staff-id", "E1", 1, 1, List.of(), List.of());
        when(store.read(TENANT, USER)).thenReturn(expected);
        assertThat(service.read(actor, null)).isSameAs(expected);
    }
    @Test void ordinaryUserCannotReadAnotherAccountEvenWhenTheyKnowItsId() {
        when(access.currentUser(actor)).thenReturn(current(USER, Set.of(), Set.of("analytics:dashboard:read")));
        assertThatThrownBy(() -> service.read(actor, OTHER)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(store);
        verify(access, never()).currentUser(new PortalAccessQuery("TENANT", OTHER, TENANT));
    }
    @Test void tenantAdminMustAlsoHaveExistingScopeManagementPermission() {
        when(access.currentUser(actor)).thenReturn(current(USER, Set.of("TENANT_SUPER_ADMIN"), Set.of("analytics:dashboard:read")));
        assertThatThrownBy(() -> service.read(actor, OTHER)).isInstanceOf(AccessDeniedException.class);
        when(access.currentUser(actor)).thenReturn(current(USER, Set.of("TENANT_SUPER_ADMIN"), Set.of("analytics:dashboard:read", "iam:data-scope:write")));
        when(access.currentUser(new PortalAccessQuery("TENANT", OTHER, TENANT))).thenReturn(current(OTHER, Set.of(), Set.of("analytics:dashboard:read")));
        service.read(actor, OTHER);
        verify(store).read(TENANT, OTHER);
    }
    @Test void targetWithoutBiAccessAndPlatformIdentityAreDenied() {
        when(access.currentUser(actor)).thenReturn(current(USER, Set.of("TENANT_SUPER_ADMIN"), Set.of("analytics:dashboard:read", "iam:data-scope:write")));
        when(access.currentUser(new PortalAccessQuery("TENANT", OTHER, TENANT))).thenReturn(current(OTHER, Set.of(), Set.of()));
        assertThatThrownBy(() -> service.read(actor, OTHER)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.read(new PortalAccessQuery("PLATFORM", USER, null), USER)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(store);
    }
    private static PortalCurrentUser current(UUID id, Set<String> roles, Set<String> permissions) {
        return new PortalCurrentUser(id, TENANT, "tenant", "TENANT", "account", "display", roles, permissions);
    }
}
