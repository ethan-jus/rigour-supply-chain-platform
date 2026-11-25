package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.*;
import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.integration.application.port.out.DhbPageSyncJobStore;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class DhbPageSyncJobServiceTest {
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), id = UUID.randomUUID();
    final Instant now = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    void multiDayInterruptionRespectsCrmWindowLimitAndChecksEverySlice() {
        var store = mock(DhbPageSyncJobStore.class);
        var crm = mock(CrmDhbDomainSyncClient.class);
        var started = now.minus(Duration.ofHours(36));
        var boundary = started.plus(Duration.ofDays(1));
        var job = new DhbPageSyncJob(id, connector, "BUSINESS_CHAIN", "RUNNING", "中断",
                started, started.plusSeconds(40), null, null);
        var active = new com.rigour.merchant.api.v1.model.CustomerSyncJob(UUID.randomUUID(), connector,
                "RUNNING", "处理中", now, now, null);
        when(crm.customerJobs(any(), eq(connector), any(), any())).thenAnswer(call -> {
            Instant from = call.getArgument(2), to = call.getArgument(3);
            assertThat(Duration.between(from, to)).isLessThanOrEqualTo(Duration.ofDays(1));
            return from.equals(boundary) ? List.of(active) : List.of();
        });
        var service = new DhbPageSyncJobService(mock(DhbSyncOrchestrationService.class), store,
                Clock.fixed(now, ZoneOffset.UTC), crm);
        try {
            service.reconcile(tenant, job);
            verify(crm).customerJobs(any(), eq(connector), eq(started), eq(boundary));
            verify(crm).customerJobs(any(), eq(connector), eq(boundary), eq(now));
            verify(store, never()).finishAbandoned(any(), any(), any());
            when(crm.customerJobs(any(), eq(connector), eq(boundary), eq(now))).thenReturn(List.of());
            service.reconcile(tenant, job);
            verify(store).finishAbandoned(tenant, job, now);
            clearInvocations(store);
            var completed = new com.rigour.merchant.api.v1.model.CustomerSyncJob(UUID.randomUUID(), connector,
                    "SUCCEEDED", "完成", now, now, null);
            when(crm.customerJobs(any(), eq(connector), any(), any())).thenReturn(List.of(completed, completed));
            service.reconcile(tenant, job);
            verify(store, never()).finishAbandoned(any(), any(), any());
        } finally { service.close(); }
    }

    @Test
    void staleRunningJobChecksRemoteWorkBeforeAtomicRecovery() {
        var store = mock(DhbPageSyncJobStore.class);
        var crm = mock(CrmDhbDomainSyncClient.class);
        var job = new DhbPageSyncJob(id, connector, "BUSINESS_CHAIN", "RUNNING", "中断",
                now.minusSeconds(3600), now.minusSeconds(3500), null, null);
        var remote = new com.rigour.merchant.api.v1.model.CustomerSyncJob(UUID.randomUUID(), connector,
                "RUNNING", "处理中", now, now, null);
        when(crm.customerJobs(any(), eq(connector), any(), any())).thenReturn(List.of(remote), List.of());
        var service = new DhbPageSyncJobService(mock(DhbSyncOrchestrationService.class), store,
                Clock.fixed(now, ZoneOffset.UTC), crm);
        try {
            service.reconcile(tenant, job);
            verify(store, never()).finishAbandoned(any(), any(), any());
            service.reconcile(tenant, job);
            verify(store).finishAbandoned(tenant, job, now);
        } finally { service.close(); }
    }

    @Test
    void reconcilesOnlyThePersistedDependencyAndLeavesWholeChainFailedForNextIncrementalRun() {
        var store = mock(DhbPageSyncJobStore.class);
        var crm = mock(CrmDhbDomainSyncClient.class);
        var dep = UUID.randomUUID();
        var job = new DhbPageSyncJob(id, connector, "BUSINESS_CHAIN", "UNKNOWN", "待核实",
                now.minusSeconds(600), now, now, null, dep);
        when(crm.customerJob(any(), eq(dep))).thenReturn(new com.rigour.merchant.api.v1.model.CustomerSyncJob(
                dep, connector, "FAILED", "客户同步失败", now, now, null));
        when(store.find(tenant, id)).thenReturn(Optional.of(job));
        var service = new DhbPageSyncJobService(mock(DhbSyncOrchestrationService.class), store,
                Clock.fixed(now, ZoneOffset.UTC), crm);
        try {
            service.reconcile(tenant, job);
            verify(store).finish(eq(tenant), eq(id), eq("FAILED"), contains("原计划"), isNull(), eq(now));
            verify(crm, never()).customerJobs(any(), any(), any(), any());
            verify(crm, never()).syncLatestCustomersInBackground(any(), any(), any(), anyInt(), any(), any());
        } finally { service.close(); }
    }

    @Test
    void ambiguousOrStillRunningRemoteJobDoesNotReleaseOriginalSlot() {
        var store = mock(DhbPageSyncJobStore.class);
        var crm = mock(CrmDhbDomainSyncClient.class);
        var job = new DhbPageSyncJob(id, connector, "BUSINESS_CHAIN", "UNKNOWN", "待核实",
                now.minusSeconds(600), now, now, null);
        var remote = new com.rigour.merchant.api.v1.model.CustomerSyncJob(UUID.randomUUID(), connector,
                "RUNNING", "处理中", now, now, null);
        when(crm.customerJobs(any(), eq(connector), any(), any()))
                .thenReturn(List.of(remote, remote), List.of(remote));
        var service = new DhbPageSyncJobService(mock(DhbSyncOrchestrationService.class), store,
                Clock.fixed(now, ZoneOffset.UTC), crm);
        try {
            service.reconcile(tenant, job);
            service.reconcile(tenant, job);
            verify(store, never()).finish(any(), any(), any(), any(), any(), any());
        } finally { service.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void oldUnknownWithNoCrmReceiptReleasesTheOriginalSchedule(boolean knownRequest) {
        var store = mock(DhbPageSyncJobStore.class);
        var crm = mock(CrmDhbDomainSyncClient.class);
        var ended = now.minus(Duration.ofHours(1));
        var started = ended.minusSeconds(20);
        var job = new DhbPageSyncJob(id, connector, "BUSINESS_CHAIN", "UNKNOWN", "待核实",
                started, ended, ended, null, knownRequest ? UUID.randomUUID() : null);
        when(crm.customerJobs(any(), eq(connector), eq(started), eq(ended))).thenReturn(List.of());
        var service = new DhbPageSyncJobService(mock(DhbSyncOrchestrationService.class), store,
                Clock.fixed(now, ZoneOffset.UTC), crm);
        try {
            service.reconcile(tenant, job);
            verify(store).finish(eq(tenant), eq(id), eq("FAILED"), contains("原计划"), isNull(), eq(now));
            verify(crm, never()).syncLatestCustomersInBackground(any(), any(), any(), anyInt(), any(), any());
        } finally { service.close(); }
    }

    @Test
    void legacyUniqueRemoteFinalResultReleasesSlotWithoutPretendingChainSucceeded() {
        var store = mock(DhbPageSyncJobStore.class);
        var crm = mock(CrmDhbDomainSyncClient.class);
        var job = new DhbPageSyncJob(id, connector, "BUSINESS_CHAIN", "UNKNOWN", "待核实",
                now.minusSeconds(600), now, now, null);
        var remote = new com.rigour.merchant.api.v1.model.CustomerSyncJob(UUID.randomUUID(), connector,
                "SUCCEEDED", "完成", now, now, null);
        when(crm.customerJobs(any(), eq(connector), any(), any())).thenReturn(List.of(remote));
        when(store.find(tenant, id)).thenReturn(Optional.of(job));
        var service = new DhbPageSyncJobService(mock(DhbSyncOrchestrationService.class), store,
                Clock.fixed(now, ZoneOffset.UTC), crm);
        try {
            service.reconcile(tenant, job);
            verify(store).dependency(tenant, id, remote.jobId());
            verify(store).finish(eq(tenant), eq(id), eq("FAILED"), anyString(), isNull(), eq(now));
        } finally { service.close(); }
    }

    CallerIdentity caller(UUID t) {
        var u = UUID.randomUUID();
        return new CallerIdentity(
                "TENANT",
                u,
                t,
                u,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of(),
                Set.of("integration:dhb:read", "integration:dhb:write"));
    }

    DhbPageSyncCommand command() {
        return new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, connector, null, null, 500, true);
    }

    DhbPageSyncJob job(String state, Instant at) {
        return new DhbPageSyncJob(
                id, connector, command().scope().name(), state, "test", at, at, null, null);
    }

    @Test
    void returnsBeforeWorkerCompletesAndSecondClickDoesNotExecuteAgain() throws Exception {
        var delegate = mock(DhbSyncOrchestrationService.class);
        var store = mock(DhbPageSyncJobStore.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.reserve(eq(tenant), eq(id), any(), any())).thenReturn(job("QUEUED", now));
        when(store.claim(eq(tenant), eq(id), any())).thenReturn(true, false);
        when(store.find(tenant, id)).thenReturn(Optional.of(job("RUNNING", now)));
        when(delegate.runPage(any(), any(), any(), eq(true)))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return new DhbSyncOrchestrationResult(
                                    id, "SUCCEEDED", "test", now, now, List.of());
                        });
        var service = new DhbPageSyncJobService(delegate, store, Clock.fixed(now, ZoneOffset.UTC), mock(com.rigour.integration.application.port.out.CrmDhbDomainSyncClient.class));
        try {
            assertThat(service.start(caller(tenant), id, command()).status()).isEqualTo("RUNNING");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.start(caller(tenant), id, command());
            verify(delegate, times(1)).runPage(any(), any(), any(), eq(true));
            release.countDown();
            verify(store, timeout(2000))
                    .finish(eq(tenant), eq(id), eq("SUCCEEDED"), anyString(), any(), any());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void staleHeartbeatIsUnknownWithoutReleasingOrFailingOriginalTask() {
        var delegate = mock(DhbSyncOrchestrationService.class);
        var store = mock(DhbPageSyncJobStore.class);
        when(store.find(tenant, id)).thenReturn(Optional.of(job("RUNNING", now.minusSeconds(91))));
        var service = new DhbPageSyncJobService(delegate, store, Clock.fixed(now, ZoneOffset.UTC), mock(com.rigour.integration.application.port.out.CrmDhbDomainSyncClient.class));
        try {
            assertThat(service.get(caller(tenant), id).status()).isEqualTo("UNKNOWN");
            verify(store, never()).finish(any(), any(), any(), any(), any(), any());
        } finally {
            service.close();
        }
    }

    @Test
    void shipmentWindowReturnsBeforeCompletionAndDuplicateSubmissionReusesJob() throws Exception {
        var delegate = mock(DhbSyncOrchestrationService.class);
        var store = mock(DhbPageSyncJobStore.class);
        var window =
                new DhbPageSyncCommand(
                        DhbPageSyncCommand.Scope.SHIPMENT,
                        connector,
                        now.minusSeconds(86400),
                        now,
                        100,
                        false);
        var shipmentJob =
                new DhbPageSyncJob(
                        id, connector, "SHIPMENT", "RUNNING", "发货", now, now, null, null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.reserve(eq(tenant), eq(id), eq(window), any())).thenReturn(shipmentJob);
        when(store.claim(eq(tenant), eq(id), any())).thenReturn(true, false);
        when(store.find(tenant, id)).thenReturn(Optional.of(shipmentJob));
        when(delegate.runPage(any(), eq(window), any(), eq(true)))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return new DhbSyncOrchestrationResult(
                                    id, "SUCCEEDED", "MANUAL_SHIPMENT", now, now, List.of());
                        });
        var service = new DhbPageSyncJobService(delegate, store, Clock.fixed(now, ZoneOffset.UTC), mock(com.rigour.integration.application.port.out.CrmDhbDomainSyncClient.class));
        try {
            assertThat(service.start(caller(tenant), id, window).scope()).isEqualTo("SHIPMENT");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.start(caller(tenant), id, window);
            verify(delegate, times(1)).runPage(any(), eq(window), any(), eq(true));
            verify(delegate, times(2)).validatePageTarget(any(), eq(window));
            release.countDown();
            verify(store, timeout(2000))
                    .finish(eq(tenant), eq(id), eq("SUCCEEDED"), anyString(), any(), any());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void statusLookupIsTenantScopedAndSubmissionRequiresUserPermission() {
        var delegate = mock(DhbSyncOrchestrationService.class);
        var store = mock(DhbPageSyncJobStore.class);
        var service = new DhbPageSyncJobService(delegate, store, Clock.fixed(now, ZoneOffset.UTC), mock(com.rigour.integration.application.port.out.CrmDhbDomainSyncClient.class));
        try {
            UUID other = UUID.randomUUID();
            when(store.find(other, id)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.get(caller(other), id)).hasMessageContaining("不存在");
            assertThatThrownBy(() -> service.start(null, id, command()))
                    .isInstanceOf(AuthorizationDeniedException.class);
            verify(store, never()).reserve(any(), any(), any(), any());
        } finally {
            service.close();
        }
    }

    @Test
    void shutdownWaitsForWorkerToPersistItsTerminalResult() throws Exception {
        var delegate = mock(DhbSyncOrchestrationService.class);
        var store = mock(DhbPageSyncJobStore.class);
        when(store.reserve(eq(tenant), eq(id), any(), any())).thenReturn(job("QUEUED", now));
        when(store.claim(eq(tenant), eq(id), any())).thenReturn(true);
        when(store.find(tenant, id)).thenReturn(Optional.of(job("RUNNING", now)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(delegate.runPage(any(), any(), any(), eq(true))).thenAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new DhbSyncOrchestrationResult(id, "SUCCEEDED", "MANUAL", now, now, List.of());
        });
        var service = new DhbPageSyncJobService(delegate, store, Clock.fixed(now, ZoneOffset.UTC), mock(CrmDhbDomainSyncClient.class));
        try {
            service.start(caller(tenant), id, command());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var closing = CompletableFuture.runAsync(service::close);
            assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            verify(store).finish(eq(tenant), eq(id), eq("SUCCEEDED"), anyString(), any(), any());
            assertThat(service.isStopping()).isTrue();
        } finally { release.countDown(); service.close(); }
    }

    @Test
    void lostDownstreamOutcomeIsNotReportedAsFailedOrRetried() {
        var delegate = mock(DhbSyncOrchestrationService.class);
        var store = mock(DhbPageSyncJobStore.class);
        when(store.reserve(eq(tenant), eq(id), any(), any())).thenReturn(job("QUEUED", now));
        when(store.claim(eq(tenant), eq(id), any())).thenReturn(true);
        when(store.find(tenant, id)).thenReturn(Optional.of(job("RUNNING", now)));
        when(delegate.runPage(any(), any(), any(), eq(true)))
                .thenThrow(new CrmDhbDomainSyncClient.OutcomeUnknown("lost", null));
        var service = new DhbPageSyncJobService(delegate, store, Clock.fixed(now, ZoneOffset.UTC), mock(com.rigour.integration.application.port.out.CrmDhbDomainSyncClient.class));
        try {
            service.start(caller(tenant), id, command());
            verify(store, timeout(2000))
                    .finish(eq(tenant), eq(id), eq("UNKNOWN"), anyString(), isNull(), any());
            verify(delegate, times(1)).runPage(any(), any(), any(), eq(true));
        } finally {
            service.close();
        }
    }
}
