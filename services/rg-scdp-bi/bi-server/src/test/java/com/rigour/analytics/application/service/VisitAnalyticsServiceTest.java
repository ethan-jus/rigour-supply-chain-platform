package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.VisitAnalyticsStore;
import com.rigour.shared.core.exception.BusinessException;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 权限收紧和日期范围校验必须发生在访问快照之前。 */
class VisitAnalyticsServiceTest {
    @Test void resolvesSelfScopeAndDefaultsToShanghaiMonth() {
        var store = mock(VisitAnalyticsStore.class);
        var scopes = mock(BiDataScopeService.class);
        when(scopes.resolve(null,null)).thenReturn(new BiDataScopeService.ScopedSelection("T","BJ","E1",false));
        var now = Instant.parse("2026-09-14T23:00:00Z");
        var service = new VisitAnalyticsService(store,scopes,Clock.fixed(now,ZoneOffset.UTC));
        service.report(null,null,null,null);
        verify(store).read("T",Instant.parse("2026-08-31T16:00:00Z"),now,"BJ","E1");
        assertThatThrownBy(() -> service.report(now,Instant.EPOCH,null,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.report(Instant.EPOCH,now,null,null)).isInstanceOf(BusinessException.class);
        verifyNoMoreInteractions(store);
    }
    @Test void deniedScopeNeverReadsData() {
        var store = mock(VisitAnalyticsStore.class);
        var scopes = mock(BiDataScopeService.class);
        when(scopes.resolve(null,null)).thenThrow(new IllegalStateException("denied"));
        var service = new VisitAnalyticsService(store,scopes,Clock.systemUTC());
        assertThatThrownBy(() -> service.report(null,null,null,null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }
}
