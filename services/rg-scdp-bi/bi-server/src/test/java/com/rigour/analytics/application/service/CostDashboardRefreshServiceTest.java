package com.rigour.analytics.application.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

import com.rigour.analytics.application.port.out.BiScheduleCoordinator;
import com.rigour.analytics.application.port.out.CostDashboardRefresh;
import com.rigour.analytics.application.port.out.CostDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

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

    @Test
    void lockConflictsRetryTheWholeProjectionAndDoNotMarkRecoveredRefreshFailed() {
        doThrow(new CannotAcquireLockException("deadlock"))
                .doThrow(new CannotAcquireLockException("deadlock"))
                .doNothing().when(refresh).refresh(tenant);
        service(true, false).refreshTenant(tenant);
        verify(refresh, times(3)).refresh(tenant);
        verify(refresh, never()).failed(tenant);
    }

    @Test
    void registrationLockConflictIsAlsoRetried() {
        doThrow(new CannotAcquireLockException("deadlock")).doNothing().when(store).register(tenant);
        service(true, false).refreshTenant(tenant);
        verify(store, times(2)).register(tenant);
        verify(refresh).refresh(tenant);
    }

    @Test
    void exhaustedLockRetriesAndFailureMarkerStillReturnAControlledFailure() {
        var lockFailure = new CannotAcquireLockException("deadlock");
        var markingFailure = new CannotAcquireLockException("marking deadlock");
        doThrow(lockFailure).when(refresh).refresh(tenant);
        doThrow(markingFailure).when(refresh).failed(tenant);
        assertThatThrownBy(() -> service(true, false).refreshTenant(tenant))
                .isInstanceOf(CostDashboardFailure.class)
                .hasCause(lockFailure);
        assertThat(lockFailure.getSuppressed()).containsExactly(markingFailure);
        verify(refresh, times(3)).refresh(tenant);
        verify(refresh).failed(tenant);
    }
}
