package com.rigour.merchant.application.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.application.port.out.*;
import com.rigour.merchant.api.v1.model.StoreWorkModels.CheckIn;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.time.Clock;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StoreWorkServiceTest {
    @Test void invisibleStoreCannotTriggerRecoveryOrCreateVisit(){
        var pools=mock(StorePoolService.class);var visits=mock(StoreVisitStore.class);var cycles=mock(StoreLifecycleStore.class);var lifecycle=mock(StoreLifecycleService.class);
        when(pools.scopeForStore("crm:store:visit-write","123")).thenThrow(new AuthorizationDeniedException("store-area"));
        var service=new StoreWorkService(pools,visits,cycles,lifecycle,Clock.systemUTC(),mock(StoreVisitCaptureClient.class));
        assertThatThrownBy(()->service.checkIn("123",new CheckIn("test_visibility_request","456",BigDecimal.ONE,BigDecimal.ONE,"GCJ02",null))).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(visits,cycles,lifecycle);
    }
    @Test void evidenceReadMustPassStoreScopeBeforeReadingCapture(){
        var pools=mock(StorePoolService.class);var visits=mock(StoreVisitStore.class);
        when(pools.scopeForStore("crm:store:history-read","123")).thenThrow(new AuthorizationDeniedException("store-area"));
        var service=new StoreWorkService(pools,visits,mock(StoreLifecycleStore.class),mock(StoreLifecycleService.class),Clock.systemUTC(),mock(StoreVisitCaptureClient.class));
        assertThatThrownBy(()->service.evidenceAccess("123","456")).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(visits);
    }

}
