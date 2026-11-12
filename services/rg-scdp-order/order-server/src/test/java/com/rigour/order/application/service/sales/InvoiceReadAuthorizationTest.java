package com.rigour.order.application.service.sales;

import com.rigour.order.application.port.out.*;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.shared.context.*;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class InvoiceReadAuthorizationTest {
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    void caller(String... permissions) {
        var id = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",id,UUID.randomUUID(),id,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of(permissions)));
    }
    @SuppressWarnings("unchecked")
    @Test void invoiceOnlyRoleReadsListAndDetailButCannotWriteOrReadOrders() {
        var invoices = mock(OrderInvoiceStore.class);
        var orders = mock(OrderRegisterStore.class);
        ObjectProvider<FundAttachmentUrlResolver> urls = mock(ObjectProvider.class);
        when(urls.getIfAvailable(any())).thenReturn(FundAttachmentUrlResolver.NONE);
        when(invoices.page(any(),anyInt(),anyInt(),any())).thenReturn(new OrderRegisterPage<>(0,0,20,List.of(),Map.of(),null));
        var service = new OrderInvoiceService(invoices,orders,mock(InvoiceAttachmentStorage.class),urls);
        caller("order:invoice:read");
        service.page(0,20,null,null,null,null,null);
        service.view("A001");
        verify(invoices).findByOrderNo(any(),eq("A001"));
        verifyNoInteractions(orders);
        assertThatThrownBy(() -> service.apply(null)).isInstanceOf(AuthorizationDeniedException.class);
        caller("order:payment:read");
        assertThatThrownBy(() -> service.page(0,20,null,null,null,null,null)).isInstanceOf(AuthorizationDeniedException.class);
        caller("order:read");
        service.view("A001");
    }
}
