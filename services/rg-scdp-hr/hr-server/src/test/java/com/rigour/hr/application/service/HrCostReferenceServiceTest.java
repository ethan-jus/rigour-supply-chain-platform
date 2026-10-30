package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.application.port.out.HrCostReferenceStore;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

class HrCostReferenceServiceTest {
    final HrCostReferenceStore store = mock(HrCostReferenceStore.class);
    final HrCostReferenceService service = new HrCostReferenceService(store);
    final UUID tenant = UUID.randomUUID();

    void actor(String permission) {
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(permission)));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "erp:cost:purchase:read",
                "erp:cost:loss:write",
                "erp:cost:rental:confirm",
                "erp:cost:marketing:read",
                "order:logistics-cost:write"
            })
    void employeeReferencesKeepTheCallingCostPermissionAndTenant(String action) {
        actor(action);
        service.employees(action);
        verify(store).employees(tenant.toString(), action);
    }

    @Test
    void rejectsUnrelatedOrMissingPermissionsBeforeReadingEmployees() {
        actor("erp:cost:purchase:read");
        assertThatThrownBy(() -> service.employees("hr:employee:read"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.employees("order:logistics-cost:write"))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
}
