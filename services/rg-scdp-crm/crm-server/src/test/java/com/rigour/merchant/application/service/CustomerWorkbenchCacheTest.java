package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.merchant.application.port.out.CustomerWorkbenchStore;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import com.rigour.shared.cache.*;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;

class CustomerWorkbenchCacheTest {
    final StorePoolService pools=mock(StorePoolService.class);
    final CustomerWorkbenchStore data=mock(CustomerWorkbenchStore.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"),ZoneOffset.UTC);
    final CustomerWorkbenchService service=new CustomerWorkbenchService(pools,data,clock);
    final LocalDate day=LocalDate.of(2026,11,12);
    long revision=1;
    @BeforeEach void setup() {
        var payloads=new HashMap<String,byte[]>();
        CacheStore store=new CacheStore() {
            public Optional<byte[]> get(String tenant,String key){return Optional.ofNullable(payloads.get(tenant+key));}
            public void put(String tenant,String key,byte[] value,Duration ttl){payloads.put(tenant+key,value);}
            public void evict(String tenant,String key){payloads.remove(tenant+key);}
            public boolean tryLock(String tenant,String key,String token,Duration lease){return true;}
            public void unlock(String tenant,String key,String token){}
        };
        CacheVersions versions=new CacheVersions() {
            public long current(String tenant){return revision;}
            public void changed(String tenant){revision++;}
            public boolean dirty(String tenant){return false;}
        };
        var cache=new QueryCache(store,versions,()->AuthorizationContext.current().orElse(null),()->false,null,"test",100000,4);
        ReflectionTestUtils.setField(service,"queryCache",cache);
        caller(true);
        when(pools.scope("crm:pool:private-read")).thenReturn(scope("crm:pool:private-read","employee",1));
        when(pools.scope("crm:pool:public-read")).thenReturn(scope("crm:pool:public-read","employee",1));
        when(data.customerCount(any(),anyString(),any(),any())).thenReturn(7L);
        when(data.newContactCount(any(),any(),any())).thenReturn(2L);
        when(pools.count(any(),any())).thenReturn(3L);
    }
    void caller(boolean orders) {
        var id=UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReflectionTestUtils.invokeMethod(AuthorizationContext.class,"set",new CallerIdentity("TENANT",id,id,id,null,id,1,1,1,Set.of(),orders?Set.of("order:read"):Set.of()));
    }
    StoreScope scope(String action,String employee,long version) {
        return new StoreScope(new StoreAccessView("tenant","user",action,version,employee,"code","name","department",Set.of(),List.of(),false,List.of()),new AdminDivisionCatalog("v1","test","test",List.of()),Map.of());
    }
    @AfterEach void clear(){ReflectionTestUtils.invokeMethod(AuthorizationContext.class,"clear");}

    @Test void cacheHitRechecksBothScopesButNeverLoadsDiscardedListRows() {
        var first=service.summary(day,day);var second=service.summary(day,day);
        assertThat(second).isEqualTo(first);assertThat(second.purchasing()).isEqualTo(7L);
        verify(pools,times(2)).scope("crm:pool:private-read");verify(pools,times(2)).scope("crm:pool:public-read");
        verify(data,times(5)).customerCount(any(),anyString(),any(),any());
        verify(data,times(1)).newContactCount(any(),any(),any());verify(pools,times(4)).count(any(),any());
        verify(data,never()).customers(any(),any(),any(),any(),any(),any(),any(),anyInt());
        verify(data,never()).contacts(any(),anyBoolean(),any(),any(),any(),any(),anyInt());verify(pools,never()).list(any());
    }
    @Test void committedDataVersionAndChangedAuthorityCannotReusePreviousSummary() {
        service.summary(day,day);revision++;service.summary(day,day);
        when(pools.scope("crm:pool:private-read")).thenReturn(scope("crm:pool:private-read","other",2));
        service.summary(day,day);service.summary(day.minusDays(1),day);
        verify(data,times(20)).customerCount(any(),anyString(),any(),any());
    }
    @Test void publicRevocationRemovesPublicCountAndPrivateRevocationStillDeniesWarmCache() {
        assertThat(service.summary(day,day).publicPool()).isEqualTo(3L);
        when(pools.scope("crm:pool:public-read")).thenThrow(new AuthorizationDeniedException("revoked"));
        assertThat(service.summary(day,day).publicPool()).isNull();
        when(pools.scope("crm:pool:private-read")).thenThrow(new AuthorizationDeniedException("revoked"));
        assertThatThrownBy(()->service.summary(day,day)).isInstanceOf(AuthorizationDeniedException.class);
    }
    @Test void losingOrderPermissionDoesNotReuseFinancialCounters() {
        service.summary(day,day);caller(false);
        assertThat(service.summary(day,day).purchasing()).isNull();
        verify(data,times(5)).customerCount(any(),anyString(),any(),any());
    }
    @Test void publicOnlyCounterDoesNotRequirePrivateOrOrderPermissionAndNeverLoadsRows() {
        caller(false);
        when(pools.scope("crm:pool:private-read")).thenThrow(new AuthorizationDeniedException("private-denied"));
        assertThat(service.publicCount().total()).isEqualTo(3L);
        verify(pools).scope("crm:pool:public-read");
        verify(pools,never()).scope("crm:pool:private-read");
        verify(pools).count(any(),argThat(q -> "PUBLIC".equals(q.view())));
        verify(pools,never()).list(any()); verifyNoInteractions(data);
        when(pools.scope("crm:pool:public-read")).thenThrow(new AuthorizationDeniedException("revoked"));
        assertThatThrownBy(() -> service.publicCount()).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test void nearbyCacheRechecksAuthorityAndSeparatesSelectionAndDataRevision() {
        var near=new com.rigour.merchant.api.v1.model.NearbyModels.Query(120d,30d,"WGS84",null);
        var origin=new com.rigour.merchant.api.v1.model.NearbyModels.Origin(120,30,120.005,30.002);
        var page=new com.rigour.merchant.api.v1.model.NearbyModels.Page<com.rigour.merchant.api.v1.model.CustomerWorkbenchModels.Item>(0,List.of(),null,origin);
        when(pools.nearbyOrigin(any(StoreScope.class),any())).thenReturn(origin);
        when(data.nearby(any(),any(),any(),any(),any(),any(),any(),anyInt(),any())).thenReturn(page);
        assertThat(service.nearby("COOPERATED",day,day,null,null,20,near)).isEqualTo(page);
        assertThat(service.nearby("COOPERATED",day,day,null,null,20,near)).isEqualTo(page);
        verify(data,times(1)).nearby(any(),any(),any(),any(),any(),any(),any(),anyInt(),any());
        service.nearby("COOPERATED",day,day,"keyword",null,20,near);revision++;
        service.nearby("COOPERATED",day,day,null,null,20,near);
        verify(data,times(3)).nearby(any(),any(),any(),any(),any(),any(),any(),anyInt(),any());
        caller(false);
        assertThatThrownBy(()->service.nearby("COOPERATED",day,day,null,null,20,near)).isInstanceOf(AuthorizationDeniedException.class);
        caller(true);when(pools.scope("crm:pool:private-read")).thenThrow(new AuthorizationDeniedException("revoked"));
        assertThatThrownBy(()->service.nearby("COOPERATED",day,day,null,null,20,near)).isInstanceOf(AuthorizationDeniedException.class);
    }
}
