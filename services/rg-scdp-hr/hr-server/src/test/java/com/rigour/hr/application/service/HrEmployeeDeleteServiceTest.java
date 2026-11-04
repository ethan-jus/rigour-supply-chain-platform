package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import java.util.*;

class HrEmployeeDeleteServiceTest {
    final HrOrganizationStore store = mock(HrOrganizationStore.class);
    final HrOrganizationService service = new HrOrganizationService(
            store, mock(HrEmployeeStore.class), mock(HrEmployeeCityScopeService.class));
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();

    void authorize(Set<String> permissions, UUID tenantId) {
        TestAuthorizationContext.set(new CallerIdentity(tenantId == null ? "SERVICE" : "TENANT", user, tenantId, tenantId == null ? null : user, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), permissions));
    }

    @AfterEach void clear() { TestAuthorizationContext.clear(); }

    @Test void editPermissionCannotDelete() {
        authorize(Set.of("hr:employee:read", "hr:employee:update"), tenant);
        assertThatThrownBy(() -> service.deleteEmployee(7L, 2)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test void requiresTenantAndValidRevision() {
        authorize(Set.of("hr:employee:delete"), null);
        assertThatThrownBy(() -> service.deleteEmployee(7L, 2)).isInstanceOf(AuthorizationDeniedException.class);
        authorize(Set.of("hr:employee:delete"), tenant);
        assertThatThrownBy(() -> service.deleteEmployee(7L, 0)).hasMessage("员工 ID 或版本无效");
        assertThatThrownBy(() -> service.deleteEmployee(null, 2)).hasMessage("员工 ID 或版本无效");
        verifyNoInteractions(store);
    }

    @Test void passesTrustedTenantActorAndExpectedRevision() {
        authorize(Set.of("hr:employee:delete"), tenant);
        service.deleteEmployee(7L, 2);
        verify(store).deleteEmployee(tenant.toString(), 7L, 2, user.toString());
    }
}
