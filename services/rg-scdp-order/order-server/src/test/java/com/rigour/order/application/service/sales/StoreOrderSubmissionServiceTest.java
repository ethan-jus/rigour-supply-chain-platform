package com.rigour.order.application.service.sales;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.rigour.order.application.port.out.*;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.merchant.api.v1.model.StoreWorkModels.OrderPermit;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.StateConflictException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class StoreOrderSubmissionServiceTest {
    @AfterEach void clear(){TestAuthorizationContext.clear();}
    @Test void concurrentRetryReturnsTheCompletedOrderWithoutChangingEffectiveTime(){
        UUID tenant=UUID.randomUUID(), user=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("order:submit")));
        var store=mock(StoreOrderSubmissionStore.class);var client=mock(StoreWorkAuthorizationClient.class);var orders=mock(OrderSalesOrderService.class);
        Instant time=Instant.parse("2026-10-28T00:00:00Z");
        var service=new StoreOrderSubmissionService(store,client,orders,Clock.fixed(time,ZoneOffset.UTC));
        String request="test_concurrent_request";
        when(store.reserve(any(),any(),eq(1L),eq(1),eq("2"),eq("3"),eq(request),eq(time)))
            .thenReturn(new StoreOrderSubmissionStore.Submission("2","3",request,"PENDING_AUTH",2),new StoreOrderSubmissionStore.Submission("2","3",request,"COMPLETED",3));
        var permit=new OrderPermit("2","3","4","5","测试门店",7,time.minusSeconds(1));
        when(client.authorize(eq("2"),any())).thenReturn(permit);
        doThrow(new StateConflictException("另一请求已完成")).when(store).authorize(any(),any(),eq(1L),eq(request),eq(permit),eq(time));
        var detail=mock(SalesOrderDetailView.class);when(orders.salesOrder(1L)).thenReturn(detail);
        assertThat(service.submit(1L,new StoreOrderSubmissionService.Command("2","3",request,1))).isSameAs(detail);
        verify(orders,never()).submit(anyLong(),anyInt());
    }
}
