package com.rigour.analytics.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.BiDataScopeStore.*;
import com.rigour.analytics.application.port.out.SalesDashboardStore;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

class SalesDashboardServiceTest {
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-24T00:00:00Z");
    private final BiDataScopeStore scopeStore = mock(BiDataScopeStore.class);
    private final SalesDashboardStore data = mock(SalesDashboardStore.class);
    private final SalesDashboardService service =
            new SalesDashboardService(data, new BiDataScopeService(scopeStore));

    @BeforeEach
    void setup() {
        com.rigour.analytics.TestRoleScope.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of("sales"),
                        Set.of("analytics:dashboard:read")));
    }

    @AfterEach
    void clear() {
        com.rigour.analytics.TestRoleScope.clear();
    }

    @Test
    void queryUsesCurrentTenantAndKeepsBusinessFiltersSeparateFromAuthorization() {
        service.query(now, now, "HZ", "E2", null, null, null);
        verify(data)
                .query(
                        eq(tenant.toString()),
                        argThat(
                                f ->
                                        "HZ".equals(f.regionCode())
                                                && "E2".equals(f.ownerStaffCode())));
        verifyNoInteractions(scopeStore);
    }
}
