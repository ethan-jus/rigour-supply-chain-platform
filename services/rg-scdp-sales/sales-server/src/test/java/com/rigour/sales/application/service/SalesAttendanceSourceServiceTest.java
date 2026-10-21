package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;
import com.rigour.sales.application.port.out.SalesAttendanceSourceStore;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

class SalesAttendanceSourceServiceTest {
    final SalesAttendanceSourceStore store = mock(SalesAttendanceSourceStore.class);
    final SalesAttendanceSourceService service = new SalesAttendanceSourceService(store);
    final UUID tenant = UUID.randomUUID(), principal = UUID.randomUUID();
    final Query query =
            new Query(List.of("E1"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    void identity(String type, Set<String> actions) {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        type,
                        principal,
                        tenant,
                        "SERVICE".equals(type) ? null : principal,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        "SERVICE".equals(type) ? 0 : 1,
                        Set.of(),
                        actions));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void browserCannotReadEvenWithNamedPermission() {
        identity("TENANT", Set.of("sales:attendance:source-read"));
        assertThatThrownBy(() -> service.evidence(query))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void serviceNeedsPermissionAndCannotRequestUnboundedHistory() {
        identity("SERVICE", Set.of());
        assertThatThrownBy(() -> service.evidence(query))
                .isInstanceOf(AuthorizationDeniedException.class);
        identity("SERVICE", Set.of("sales:attendance:source-read"));
        assertThatThrownBy(
                        () ->
                                service.evidence(
                                        new Query(
                                                List.of("E1"),
                                                query.from(),
                                                LocalDate.of(2026, 10, 1))))
                .hasMessageContaining("同月");
        service.evidence(query);
        verify(store).evidence(tenant.toString(), query);
    }
}
