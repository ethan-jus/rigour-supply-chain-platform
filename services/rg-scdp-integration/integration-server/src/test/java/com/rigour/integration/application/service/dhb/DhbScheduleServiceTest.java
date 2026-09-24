package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.scheduling.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class DhbScheduleServiceTest {
    private final SyncScheduleStore store = mock(SyncScheduleStore.class);
    private final DhbPageSyncJobStore jobs = mock(DhbPageSyncJobStore.class);
    private final DhbPageSyncJobService worker = mock(DhbPageSyncJobService.class);
    private final DhbSyncOrchestrationService orchestration =
            mock(DhbSyncOrchestrationService.class);
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");
    private final DhbScheduleService service =
            new DhbScheduleService(
                    store, jobs, worker, orchestration, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID();

    private CallerIdentity actor(Set<String> permissions) {
        var id = UUID.randomUUID();
        return new CallerIdentity(
                "TENANT", id, tenant, id, null, UUID.randomUUID(), 0, 0, 0, Set.of(), permissions);
    }

    private ScheduleView plan(String key, String job, Instant started) {
        return new ScheduleView(
                key,
                new ScheduleSettings(true, "FIXED_DELAY", 60, null),
                1,
                true,
                now,
                started,
                null,
                null,
                null,
                job);
    }

    @Test
    void configuringChainRequiresBothIntegrationWriteAndHrSync() {
        var command =
                new SaveScheduleCommand(new ScheduleSettings(false, "FIXED_DELAY", 60, null), 0);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        actor(
                                                Set.of(
                                                        "integration:dhb:read",
                                                        "integration:dhb:write")),
                                        connector,
                                        command))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, orchestration);
    }

    @Test
    void planStorageUsesAuthenticatedTenantAndSaveDoesNotStartSync() {
        var who =
                actor(Set.of("integration:dhb:read", "integration:dhb:write", "hr:employee:sync"));
        var command =
                new SaveScheduleCommand(new ScheduleSettings(true, "FIXED_DELAY", 60, null), 0);
        service.save(who, connector, command);
        verify(store)
                .save(
                        tenant.toString(),
                        connector.toString(),
                        command,
                        who.principalId().toString(),
                        now);
        verifyNoInteractions(worker);
    }

    @Test
    void biPlansAreNeverExecutedByDhbScheduler() {
        var bi =
                new SyncScheduleStore.Entry(
                        tenant.toString(),
                        plan("BI_REFRESH", UUID.randomUUID().toString(), now.minusSeconds(300)),
                        now);
        when(store.running()).thenReturn(List.of(bi));
        when(store.due(now)).thenReturn(List.of(bi));
        service.tick();
        verifyNoInteractions(worker, jobs);
        verify(store, never()).claim(any(), any(), anyLong(), any(), any());
    }

    @Test
    void failedAtomicClaimNeverSubmitsWorker() {
        when(store.due(now))
                .thenReturn(
                        List.of(
                                new SyncScheduleStore.Entry(
                                        tenant.toString(),
                                        plan(connector.toString(), null, null),
                                        null)));
        service.tick();
        verifyNoInteractions(worker);
    }

    @Test
    void missingJobAfterGracePeriodIsHeldUnknownNotResubmitted() {
        var token = UUID.randomUUID().toString();
        when(store.running())
                .thenReturn(
                        List.of(
                                new SyncScheduleStore.Entry(
                                        tenant.toString(),
                                        plan(connector.toString(), token, now.minusSeconds(121)),
                                        now)));
        service.tick();
        verify(store)
                .finish(
                        eq(tenant.toString()),
                        eq(connector.toString()),
                        eq(token),
                        eq("UNKNOWN"),
                        anyString(),
                        eq(now));
        verifyNoInteractions(worker);
    }
}
