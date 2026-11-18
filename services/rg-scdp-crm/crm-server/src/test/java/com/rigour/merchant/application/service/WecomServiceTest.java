package com.rigour.merchant.application.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.merchant.api.v1.model.WecomModels.*;
import com.rigour.merchant.application.port.out.WecomStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class WecomServiceTest {
    final String tenant=UUID.randomUUID().toString(); final Instant now=Instant.parse("2026-11-17T08:00:00Z");
    final WecomStore store=mock(WecomStore.class); final StorePoolService pools=mock(StorePoolService.class);
    final WecomService service=new WecomService(pools,store,Clock.fixed(now,ZoneOffset.UTC));
    Snapshot snapshot(boolean complete){return new Snapshot(tenant,"corp:app","2","销售",1,now,complete,List.of(new CustomerFact("external","客户",now,"BASELINE")));}
    void caller(String scope,String callerTenant,Set<String> permissions)throws Exception{
        var setter=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);setter.setAccessible(true);var principal=UUID.randomUUID();
        setter.invoke(null,new CallerIdentity(scope,principal,UUID.fromString(callerTenant),"TENANT".equals(scope)?principal:null,null,UUID.randomUUID(),1,1,"TENANT".equals(scope)?1:0,Set.of(),permissions));
    }
    @AfterEach void clear()throws Exception{var method=AuthorizationContext.class.getDeclaredMethod("clear");method.setAccessible(true);method.invoke(null);}
    @Test void manuallyGrantedSourcePermissionNeverLetsASalesUserForgeWecom()throws Exception{
        caller("TENANT",tenant,Set.of("crm:wecom:source-write"));assertThatThrownBy(()->service.accept(snapshot(true))).isInstanceOf(AuthorizationDeniedException.class);
        caller("SERVICE",UUID.randomUUID().toString(),Set.of("crm:wecom:source-write"));assertThatThrownBy(()->service.accept(snapshot(true))).isInstanceOf(AuthorizationDeniedException.class);
        caller("SERVICE",tenant,Set.of());assertThatThrownBy(()->service.accept(snapshot(true))).isInstanceOf(AuthorizationDeniedException.class);verifyNoInteractions(store);
    }
    @Test void partialSnapshotsCannotDeleteMissingCustomers()throws Exception{
        caller("SERVICE",tenant,Set.of("crm:wecom:source-write"));assertThatThrownBy(()->service.accept(snapshot(false))).isInstanceOf(RequestValidationException.class);verifyNoInteractions(store);
        service.accept(snapshot(true));verify(store).accept(snapshot(true),now);
    }
}
