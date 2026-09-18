package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiComparisonView.Values;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.BiComparisonStore;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 服务端权限范围和微秒闭区间前期比较。 */
class BiComparisonServiceTest {
    @Test void singleShanghaiDayKeepsAdjacentBusinessDayAndOriginalInstantBounds() {
        var store = mock(BiComparisonStore.class);
        var scopes = mock(BiDataScopeService.class);
        when(scopes.resolve(null, null)).thenReturn(new BiDataScopeService.ScopedSelection("T", null, null, true));
        var zero = new Values(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        when(store.compare(anyString(), any(), any())).thenReturn(new BiComparisonStore.Snapshot(zero, zero, List.of()));
        var service = new BiComparisonService(store, scopes, Clock.systemUTC());
        var from = Instant.parse("2026-08-31T16:00:00Z");
        var to = Instant.parse("2026-09-01T15:59:59.999999Z");
        var result = service.compare(from, to, null, null, null, null, null);
        assertThat(result.from()).isEqualTo(from);
        assertThat(result.to()).isEqualTo(to);
        assertThat(result.previousFrom()).isEqualTo(Instant.parse("2026-08-30T16:00:00Z"));
        assertThat(result.previousTo()).isEqualTo(Instant.parse("2026-08-31T15:59:59.999999Z"));
    }
    @Test void comparisonAppliesVerifiedScopeAndAdjacentNonoverlappingWindow() {
        var store = mock(BiComparisonStore.class);
        var scopes = mock(BiDataScopeService.class);
        when(scopes.resolve(null, null)).thenReturn(new BiDataScopeService.ScopedSelection("T", "BJ", "S1", false));
        var zero = new Values(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        when(store.compare(anyString(), any(), any())).thenReturn(new BiComparisonStore.Snapshot(zero, zero, List.of()));
        var service = new BiComparisonService(store, scopes, Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC));
        var result = service.compare(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T23:59:59.999999Z"),
                null, null, "store", null, "dhb");
        assertThat(result.previousFrom()).isEqualTo(Instant.parse("2026-08-30T00:00:00Z"));
        assertThat(result.previousTo()).isEqualTo(Instant.parse("2026-08-31T23:59:59.999999Z"));
        var current = ArgumentCaptor.forClass(SupplyDashboardFilter.class);
        verify(store).compare(eq("T"), current.capture(), any());
        assertThat(current.getValue().regionCode()).isEqualTo("BJ");
        assertThat(current.getValue().ownerStaffCode()).isEqualTo("S1");
        assertThat(current.getValue().sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThatThrownBy(() -> service.compare(result.from(), result.to(), null, null, null, 1L, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.compare(Instant.MIN, Instant.MIN.plusSeconds(1),
                null, null, null, null, null)).isInstanceOf(BusinessException.class);
    }
}
