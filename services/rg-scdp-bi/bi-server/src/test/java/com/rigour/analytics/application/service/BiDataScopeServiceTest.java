package com.rigour.analytics.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.TestRoleScope;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.util.*;

class BiDataScopeServiceTest {
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID();
    private final BiDataScopeStore store = mock(BiDataScopeStore.class);
    private final BiDataScopeService service = new BiDataScopeService(store);

    private CallerIdentity caller(Set<String> permissions) {
        return new CallerIdentity(
                "TENANT",
                USER,
                TENANT,
                USER,
                null,
                UUID.randomUUID(),
                1,
                1,
                1,
                Set.of("SYS_ADMIN"),
                permissions);
    }

    @AfterEach
    void clear() {
        TestRoleScope.clear();
    }

    @Test
    void allDataUsesSavedScopeWithoutLegacyHrOrCrmProjection() {
        TestRoleScope.set(caller(Set.of("analytics:dashboard:read")), "ALL");
        assertThat(service.effective().accessLevel()).isEqualTo("TENANT");
        assertThat(service.effective().reasonCode()).isEqualTo("AUTHORIZED");
        assertThat(service.resolve(null, null).fullTenant()).isTrue();
        assertThat(service.resolve(null, null).tenantId()).isEqualTo(TENANT.toString());
        service.requireGlobalGovernance();
        verifyNoInteractions(store);
    }

    @Test
    void roleNameCannotBypassMissingMenuPermission() {
        TestRoleScope.set(caller(Set.of()), "ALL");
        assertThatThrownBy(service::effective).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void missingCurrentAuthorizationNeverFallsBackToOldRole() {
        TestAuthorizationContext.set(caller(Set.of("analytics:dashboard:read")));
        assertThatThrownBy(service::effective).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void departmentAndSelfUseSharedRowFilterAndCannotReadUnattributedTenantAggregates() {
        for (String mode : List.of("DEPARTMENT", "SELF")) {
            TestRoleScope.set(caller(Set.of("analytics:dashboard:read")), mode);
            assertThat(service.effective().accessLevel()).isEqualTo("SCOPED");
            assertThat(service.resolve("SH", "E2").fullTenant()).isFalse();
            assertThatThrownBy(service::requireGlobalGovernance)
                    .isInstanceOf(AuthorizationDeniedException.class);
            assertThat(service.effective().unavailableSubjects()).contains("INVENTORY");
            service.restrictedFilterOptions();
            assertThatThrownBy(() -> service.requireObjectScope("SH", "E2"))
                    .isInstanceOf(AuthorizationDeniedException.class);
        }
        verify(store, times(2)).filterOptions(TENANT.toString(), null, null);
    }

    @Test
    void objectActionChecksRequestedPermissionAndSavedScope() {
        TestRoleScope.set(
                caller(Set.of("analytics:dashboard:read", "analytics:action:write")), "SELF");
        when(store.appObjectVisible(TENANT.toString(), "BJ", "E1", "analytics:action:write"))
                .thenReturn(true);
        service.requireObjectActionScope("BJ", "E1", "analytics:action:write");
        assertThatThrownBy(
                        () ->
                                service.requireObjectActionScope(
                                        "BJ", "E2", "analytics:action:write"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }
}
