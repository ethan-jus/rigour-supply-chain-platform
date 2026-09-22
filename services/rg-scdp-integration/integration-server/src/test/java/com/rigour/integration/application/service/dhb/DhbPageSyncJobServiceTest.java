package com.rigour.integration.application.service.dhb;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.rigour.integration.api.v1.model.*;
import com.rigour.integration.application.port.out.DhbPageSyncJobStore;
import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.shared.context.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DhbPageSyncJobServiceTest {
    final UUID tenant=UUID.randomUUID(), connector=UUID.randomUUID(), id=UUID.randomUUID();
    final Instant now=Instant.parse("2026-09-22T08:00:00Z");
    CallerIdentity caller(UUID t) { var u=UUID.randomUUID();return new CallerIdentity("TENANT",u,t,u,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("integration:dhb:read","integration:dhb:write")); }
    DhbPageSyncCommand command(){return new DhbPageSyncCommand(DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE,connector,null,null,500,true);}
    DhbPageSyncJob job(String state, Instant at){return new DhbPageSyncJob(id,connector,command().scope().name(),state,"test",at,at,null,null);}
    @Test void returnsBeforeWorkerCompletesAndSecondClickDoesNotExecuteAgain() throws Exception {
        var delegate=mock(DhbSyncOrchestrationService.class);var store=mock(DhbPageSyncJobStore.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(store.reserve(eq(tenant),eq(id),any(),any())).thenReturn(job("QUEUED",now));
        when(store.claim(eq(tenant),eq(id),any())).thenReturn(true,false);
        when(store.find(tenant,id)).thenReturn(Optional.of(job("RUNNING",now)));
        when(delegate.runPage(any(),any(),any(),eq(true))).thenAnswer(call->{
            entered.countDown(); release.await(5,TimeUnit.SECONDS);
            return new DhbSyncOrchestrationResult(id,"SUCCEEDED","test",now,now,List.of());
        });
        var service=new DhbPageSyncJobService(delegate,store,Clock.fixed(now,ZoneOffset.UTC));
        try {
            assertThat(service.start(caller(tenant),id,command()).status()).isEqualTo("RUNNING");
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            service.start(caller(tenant),id,command());
            verify(delegate,times(1)).runPage(any(),any(),any(),eq(true));
            release.countDown();
            verify(store,timeout(2000)).finish(eq(tenant),eq(id),eq("SUCCEEDED"),anyString(),any(),any());
        } finally {release.countDown();service.close();}
    }
    @Test void staleHeartbeatIsUnknownWithoutReleasingOrFailingOriginalTask() {
        var delegate=mock(DhbSyncOrchestrationService.class);var store=mock(DhbPageSyncJobStore.class);
        when(store.find(tenant,id)).thenReturn(Optional.of(job("RUNNING",now.minusSeconds(91))));
        var service=new DhbPageSyncJobService(delegate,store,Clock.fixed(now,ZoneOffset.UTC));
        try {
            assertThat(service.get(caller(tenant),id).status()).isEqualTo("UNKNOWN");
            verify(store,never()).finish(any(),any(),any(),any(),any(),any());
        } finally {service.close();}
    }
    @Test void statusLookupIsTenantScopedAndSubmissionRequiresUserPermission() {
        var delegate=mock(DhbSyncOrchestrationService.class);var store=mock(DhbPageSyncJobStore.class);
        var service=new DhbPageSyncJobService(delegate,store,Clock.fixed(now,ZoneOffset.UTC));
        try {
            UUID other=UUID.randomUUID();
            when(store.find(other,id)).thenReturn(Optional.empty());
            assertThatThrownBy(()->service.get(caller(other),id)).hasMessageContaining("不存在");
            assertThatThrownBy(()->service.start(null,id,command())).isInstanceOf(AuthorizationDeniedException.class);
            verify(store,never()).reserve(any(),any(),any(),any());
        } finally {service.close();}
    }
    @Test void lostDownstreamOutcomeIsNotReportedAsFailedOrRetried() {
        var delegate=mock(DhbSyncOrchestrationService.class);var store=mock(DhbPageSyncJobStore.class);
        when(store.reserve(eq(tenant),eq(id),any(),any())).thenReturn(job("QUEUED",now));
        when(store.claim(eq(tenant),eq(id),any())).thenReturn(true);
        when(store.find(tenant,id)).thenReturn(Optional.of(job("RUNNING",now)));
        when(delegate.runPage(any(),any(),any(),eq(true))).thenThrow(new CrmDhbDomainSyncClient.OutcomeUnknown("lost",null));
        var service=new DhbPageSyncJobService(delegate,store,Clock.fixed(now,ZoneOffset.UTC));
        try {
            service.start(caller(tenant),id,command());
            verify(store,timeout(2000)).finish(eq(tenant),eq(id),eq("UNKNOWN"),anyString(),isNull(),any());
            verify(delegate,times(1)).runPage(any(),any(),any(),eq(true));
        } finally {service.close();}
    }
}
