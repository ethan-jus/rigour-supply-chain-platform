package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView.Employee;
import com.rigour.analytics.application.port.out.EmployeeAnalyticsStore;
import com.rigour.analytics.application.port.out.EmployeeAnalyticsStore.Row;
import com.rigour.analytics.application.port.out.EmployeeAnalyticsStore.Snapshot;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 人员总体、期间事件与授权范围独立验证，不把订单人数作为人员分母。 */
class EmployeeAnalyticsServiceTest {
    private static final Instant FROM = Instant.parse("2026-08-31T16:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T15:59:59Z");
    private final EmployeeAnalyticsStore store = mock(EmployeeAnalyticsStore.class);
    private final BiDataScopeService scopes = mock(BiDataScopeService.class);
    private final EmployeeAnalyticsService service = new EmployeeAnalyticsService(store, scopes, Clock.fixed(TO, ZoneOffset.UTC));

    @Test void countsZeroOrderEmployeesAndCurrentVersusPeriodStatusSeparately() {
        when(scopes.resolve(null, null)).thenReturn(new BiDataScopeService.ScopedSelection("tenant", null, null, true));
        when(store.read("tenant", FROM, TO, null, null)).thenReturn(new Snapshot(TO, List.of(
                row("E1", "ACTIVE", null, null, 0), row("E2", "LEFT", FROM, TO, 2),
                row("E3", "INACTIVE", FROM.minusSeconds(1), null, 0))));
        var result = service.report(FROM, TO, null, null);
        assertThat(result.summary().total()).isEqualTo(3);
        assertThat(result.summary().active()).isEqualTo(1);
        assertThat(result.summary().left()).isEqualTo(1);
        assertThat(result.summary().inactive()).isEqualTo(1);
        assertThat(result.summary().orderingEmployees()).isEqualTo(1);
        assertThat(result.summary().joinedInPeriod()).isEqualTo(1);
        assertThat(result.summary().leftInPeriod()).isEqualTo(1);
        assertThat(result.summary().missingEntryDate()).isEqualTo(1);
        assertThat(result.months()).singleElement().satisfies(month -> {
            assertThat(month.month()).isEqualTo("2026-09");
            assertThat(month.joined()).isEqualTo(1);
            assertThat(month.left()).isEqualTo(1);
        });
        assertThat(result.cities()).singleElement().satisfies(city -> assertThat(city.total()).isEqualTo(3));
    }
    @Test void unavailableSnapshotDoesNotReturnZeroMetrics() {
        when(scopes.resolve(null, null)).thenReturn(new BiDataScopeService.ScopedSelection("tenant", "BJ", "E1", false));
        when(store.read("tenant", FROM, TO, "BJ", "E1")).thenReturn(new Snapshot(null, List.of()));
        var result = service.report(FROM, TO, null, null);
        assertThat(result.status()).isEqualTo("NOT_READY");
        assertThat(result.summary()).isNull();
        verify(store).read("tenant", FROM, TO, "BJ", "E1");
    }
    @Test void rejectsScopeBeforeReadingAnyEmployee() {
        when(scopes.resolve("OTHER", null)).thenThrow(new AuthorizationDeniedException("bi-city-scope"));
        assertThatThrownBy(() -> service.report(FROM, TO, "OTHER", null)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
    private static Row row(String code, String status, Instant entry, Instant leave, long orders) {
        return new Row(new Employee(code, code, status, "北京", "销售", null, entry, leave,
                0L, orders, BigDecimal.ZERO, BigDecimal.ZERO), "BJ");
    }
}
