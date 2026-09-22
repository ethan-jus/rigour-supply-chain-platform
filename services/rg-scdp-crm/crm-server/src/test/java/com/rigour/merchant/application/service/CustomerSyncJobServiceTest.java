package com.rigour.merchant.application.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.rigour.merchant.api.v1.model.*;
import com.rigour.merchant.application.port.out.CustomerSyncJobStore;
import com.rigour.shared.context.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CustomerSyncJobServiceTest {
    final UUID tenant=UUID.randomUUID(),connector=UUID.randomUUID(),id=UUID.randomUUID();
    final Instant now=Instant.parse("2026-09-22T08:00:00Z");
    CallerIdentity caller(){return new CallerIdentity("SERVICE",UUID.randomUUID(),tenant,null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("integration:dhb:read"));}
    CustomerSyncJob job(Instant heartbeat){return new CustomerSyncJob(id,connector,"RUNNING","处理中",now,heartbeat,null);}
    @Test void returnsImmediatelyAndDuplicateSubmissionDoesNotRunAgain() throws Exception {
        var store=mock(CustomerSyncJobStore.class);var delegate=mock(CrmMasterDataSyncService.class);
        when(store.reserve(eq(tenant),eq(id),eq(connector),any())).thenReturn(job(now));
        when(store.claim(eq(tenant),eq(id),any())).thenReturn(true,false);
        when(store.find(tenant,id)).thenReturn(Optional.of(job(now)));
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
        when(delegate.runSelected(any(),any(),any(),anyInt(),isNull(),isNull(),eq("CUSTOMER"),eq(true),isNull(),any(),any()))
                .thenAnswer(call->{entered.countDown();release.await(5,TimeUnit.SECONDS);return new SyncResult(id,"SUCCEEDED",List.of());});
        var service=new CustomerSyncJobService(delegate,store,Clock.fixed(now,ZoneOffset.UTC));
        try {
            assertThat(service.start(caller(),id,connector,UUID.randomUUID(),10,null).status()).isEqualTo("RUNNING");
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            service.start(caller(),id,connector,UUID.randomUUID(),10,null);
            release.countDown();
            verify(store,timeout(2000)).update(eq(tenant),eq(id),eq("SUCCEEDED"),anyString(),any(),any());
            verify(delegate,times(1)).runSelected(any(),any(),any(),anyInt(),isNull(),isNull(),eq("CUSTOMER"),eq(true),isNull(),any(),any());
        } finally {release.countDown();service.close();}
    }
    @Test void staleHeartbeatIsUnknownAndUnauthorizedRequestCannotStart() {
        var store=mock(CustomerSyncJobStore.class);var delegate=mock(CrmMasterDataSyncService.class);
        when(store.find(tenant,id)).thenReturn(Optional.of(job(now.minusSeconds(91))));
        var service=new CustomerSyncJobService(delegate,store,Clock.fixed(now,ZoneOffset.UTC));
        try {
            assertThat(service.get(caller(),id).status()).isEqualTo("UNKNOWN");
            assertThatThrownBy(()->service.start(null,id,connector,UUID.randomUUID(),10,null)).isInstanceOf(AuthorizationDeniedException.class);
            verify(store,never()).reserve(any(),any(),any(),any());
            verify(store,never()).update(any(),any(),any(),any(),any(),any());
        } finally {service.close();}
    }
}
