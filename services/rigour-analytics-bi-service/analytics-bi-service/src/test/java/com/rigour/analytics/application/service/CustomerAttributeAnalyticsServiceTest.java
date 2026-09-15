package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.CustomerAttributeAnalyticsStore;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证未同步与空档案不同、本人范围不能扩大、拒绝权限时不读取数据。 */
class CustomerAttributeAnalyticsServiceTest {
    @Test void enforcesScopeAndKeepsNotReadyDistinctFromEmpty() {
        var store = mock(CustomerAttributeAnalyticsStore.class);
        var scopes = mock(BiDataScopeService.class);
        var now = Instant.parse("2026-09-15T00:00:00Z");
        var from = Instant.parse("2026-08-31T16:00:00Z");
        var service = new CustomerAttributeAnalyticsService(store, scopes, Clock.fixed(now, ZoneOffset.UTC));
        when(scopes.resolve(null, null)).thenReturn(new BiDataScopeService.ScopedSelection("T", "BJ", "E1", false));
        when(store.read("T", from, now, "BJ", "E1")).thenReturn(new CustomerAttributeAnalyticsStore.Snapshot(null, List.of(), List.of()));
        assertThat(service.report(null, null, null, null).status()).isEqualTo("NOT_READY");
        verify(store).read("T", from, now, "BJ", "E1");
        clearInvocations(store);
        when(scopes.resolve("SH", null)).thenThrow(new AuthorizationDeniedException("bi-city-scope"));
        assertThatThrownBy(() -> service.report(null, null, "SH", null)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
}
