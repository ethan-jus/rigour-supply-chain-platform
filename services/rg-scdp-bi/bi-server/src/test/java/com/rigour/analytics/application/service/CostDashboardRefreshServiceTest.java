package com.rigour.analytics.application.service;

import static org.mockito.Mockito.*;

import com.rigour.analytics.application.port.out.BiScheduleCoordinator;
import com.rigour.analytics.application.port.out.CostDashboardRefresh;
import com.rigour.analytics.application.port.out.CostDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class CostDashboardRefreshServiceTest {
    private final CostDashboardStore store = mock(CostDashboardStore.class);
    private final CostDashboardRefresh refresh = mock(CostDashboardRefresh.class);
    private final SupplyDashboardStore supply = mock(SupplyDashboardStore.class);
    private final BiScheduleCoordinator schedules = mock(BiScheduleCoordinator.class);
    private final String tenant = UUID.randomUUID().toString();

    private CostDashboardRefreshService service(boolean enabled, boolean scheduledEnabled) {
        return new CostDashboardRefreshService(
                store, refresh, supply, enabled, scheduledEnabled, schedules);
    }

    @Test
    void legacySwitchDisablesCostScheduleAlongsideSales() {
        service(true, false).scheduled();
        verifyNoInteractions(store, refresh, supply, schedules);
    }

    @Test
    void centrallyManagedTenantDoesNotBypassCentralScheduleEvenWhenDisabled() {
        when(store.tenants()).thenReturn(List.of(tenant));
        when(schedules.managed(UUID.fromString(tenant))).thenReturn(true);
        service(true, true).scheduled();
        verifyNoInteractions(refresh);
        verify(store, never()).register(tenant);
    }

    @Test
    void unavailableCoordinatorDoesNotStartUncoordinatedRefresh() {
        when(store.tenants()).thenReturn(List.of(tenant));
        when(schedules.managed(UUID.fromString(tenant)))
                .thenThrow(new IllegalStateException("network"));
        service(true, true).scheduled();
        verifyNoInteractions(refresh);
    }

    @Test
    void legacyTenantRegistryDeduplicatesAndContinuesAfterAnotherTenantFails() {
        String other = UUID.randomUUID().toString();
        when(store.tenants()).thenReturn(List.of(tenant));
        when(supply.refreshTenantIds()).thenReturn(List.of(tenant, other));
        doThrow(new IllegalStateException("source unavailable")).when(refresh).refresh(tenant);
        service(true, true).scheduled();
        verify(refresh).refresh(tenant);
        verify(refresh).failed(tenant);
        verify(refresh).refresh(other);
    }

    @Test
    void centralDispatchUsesItsOwnScheduleDespiteLegacySwitchBeingOff() {
        service(true, false).refreshConfiguredTenant(tenant);
        verify(store).register(tenant);
        verify(refresh).refresh(tenant);
        verifyNoInteractions(schedules, supply);
    }

    @Test
    void costFeatureSwitchAlsoDisablesCentralDispatch() {
        var service = service(false, true);
        service.scheduled();
        service.refreshConfiguredTenant(tenant);
        verifyNoInteractions(store, refresh, supply, schedules);
    }
}
