package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.merchant.api.v1.model.StoreOrderResult;
import com.rigour.merchant.application.port.out.StoreFactStore;
import com.rigour.shared.context.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class StoreFactServiceTest {
    final String tenant=UUID.randomUUID().toString();
    final Instant now=Instant.parse("2026-11-06T02:00:00Z");
    final StoreFactStore store=mock(StoreFactStore.class);
    final StoreFactService service=new StoreFactService(store,Clock.fixed(now,ZoneOffset.UTC));
    StoreOrderResult result(){return new StoreOrderResult(tenant,"10","20","30","40","request",1,"COMPLETED","SUBMITTED",false,BigDecimal.TEN,now,null,now);}
    void caller(String scope,String callerTenant,Set<String> permissions)throws Exception{
        var setter=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);setter.setAccessible(true);
        UUID principal=UUID.randomUUID();
        setter.invoke(null,new CallerIdentity(scope,principal,UUID.fromString(callerTenant),"TENANT".equals(scope)?principal:null,null,UUID.randomUUID(),1,1,"TENANT".equals(scope)?1:0,Set.of(),permissions));
    }
    @AfterEach void clear()throws Exception{var method=AuthorizationContext.class.getDeclaredMethod("clear");method.setAccessible(true);method.invoke(null);}
    @Test void sourcePermissionCannotBeUsedByTenantOrAcrossTenants()throws Exception{
        caller("TENANT",tenant,Set.of("crm:store:order-source-write"));
        assertThatThrownBy(()->service.order(result())).isInstanceOf(AuthorizationDeniedException.class);
        caller("SERVICE",UUID.randomUUID().toString(),Set.of("crm:store:order-source-write"));
        assertThatThrownBy(()->service.order(result())).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
    @Test void visitSourceCannotWriteOrderFacts()throws Exception{
        caller("SERVICE",tenant,Set.of("crm:store:visit-source-write"));
        assertThatThrownBy(()->service.order(result())).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
    @Test void authorizedSourceWritesOnlyItsTenant()throws Exception{
        caller("SERVICE",tenant,Set.of("crm:store:order-source-write"));
        service.order(result());verify(store).acceptOrder(result(),now);
    }
}
