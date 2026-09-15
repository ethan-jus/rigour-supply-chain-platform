package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.CityContactAnalyticsStore;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Sales 员工映射不完整时不得退化为全城读取。 */
class CityContactAnalyticsServiceTest {
    @Test void selfScopeIsPassedToStoreAndOldSnapshotNeverFallsBackToCityCounts() {
        var store = mock(CityContactAnalyticsStore.class);
        var scopes = mock(BiDataScopeService.class);
        when(scopes.resolve(null, null)).thenReturn(new BiDataScopeService.ScopedSelection("T", "BJ", "E1", false));
        when(store.read(eq("T"), any(), any(), eq("BJ"), eq("E1")))
                .thenReturn(new CityContactAnalyticsStore.Snapshot(Instant.EPOCH, false, List.of()));
        var service = new CityContactAnalyticsService(store, scopes, Clock.systemUTC());
        assertThat(service.report(null, null, null, null).status()).isEqualTo("OWNER_MAPPING_REQUIRED");
        verify(store).read(eq("T"), any(), any(), eq("BJ"), eq("E1"));
        verifyNoMoreInteractions(store);
    }

    @Test void mappedSelfScopeReadsOnlyConfirmedEmployeeCode() {
        var store = mock(CityContactAnalyticsStore.class);
        var scopes = mock(BiDataScopeService.class);
        when(scopes.resolve(null, null)).thenReturn(new BiDataScopeService.ScopedSelection("T", "BJ", "E1", false));
        when(store.read(eq("T"), any(), any(), eq("BJ"), eq("E1")))
                .thenReturn(new CityContactAnalyticsStore.Snapshot(Instant.EPOCH, true, List.of()));
        var service = new CityContactAnalyticsService(store, scopes, Clock.systemUTC());
        assertThat(service.report(null, null, null, null).status()).isEqualTo("READY");
        verify(store).read(eq("T"), any(), any(), eq("BJ"), eq("E1"));
    }
}
