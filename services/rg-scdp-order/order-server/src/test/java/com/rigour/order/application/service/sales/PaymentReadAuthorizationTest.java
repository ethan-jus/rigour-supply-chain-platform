package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.OrderRegisterApi;
import com.rigour.order.api.v1.PaymentFilterApi.*;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.client.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentReadAuthorizationTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    private SupplyAuthorizationContext finance(String... permissions) {
        var caller = new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(),
                0, 0, 0, Set.of(), Set.of(permissions));
        TestAuthorizationContext.set(caller);
        var policy = mock(SupplyAuthorizationView.class);
        when(policy.mode()).thenReturn("ACTIVE");
        return SupplyAuthorizationContext.open(mock(SupplyAuthorizationClient.class), caller, policy);
    }
    @SuppressWarnings("unchecked")
    @Test void financeReadsPaymentsAndVouchersWithoutOrderReadButCannotWrite() {
        var store = mock(OrderRegisterStore.class);
        when(store.payments(any(), anyInt(), anyInt(), any()))
                .thenReturn(new OrderRegisterPage<>(0, 0, 20, List.of(), Map.of(), null));
        ObjectProvider<FundAttachmentUrlResolver> urls = mock(ObjectProvider.class);
        when(urls.getIfAvailable(any())).thenReturn(FundAttachmentUrlResolver.NONE);
        var registers = new OrderRegisterService(store, mock(CrmCustomerAreaDisplayClient.class),
                mock(HrEmployeeDisplayClient.class), mock(OrderInvoiceStore.class), urls);
        var vouchers = mock(PaymentVoucherStore.class);
        var service = new PaymentVoucherService(vouchers, urls);
        var audits = mock(PaymentVoucherAuditStore.class);
        var auditService = new PaymentVoucherAuditService(audits, mock(OrderStatusDictionary.class));
        try (var ignored = finance("order:payment:read")) {
            registers.payments(0, 20, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
            verify(store).payments(eq(tenant.toString()), eq(0), eq(20), any());
            service.vouchers(1);
            service.checkTransaction("T1");
            auditService.scan();
            verify(audits).payments(tenant.toString(), "order:payment:read");
            assertThatThrownBy(registers::creators).isInstanceOf(AuthorizationDeniedException.class);
            assertThatThrownBy(() -> service.recognize(1)).isInstanceOf(AuthorizationDeniedException.class);
            assertThatThrownBy(() -> auditService.review(null)).isInstanceOf(AuthorizationDeniedException.class);
            verify(vouchers, never()).requestRecognition(any(), anyLong());
        }
        try (var ignored = finance("order:read")) {
            assertThatThrownBy(() -> service.vouchers(1)).isInstanceOf(AuthorizationDeniedException.class);
        }
    }
    @Test void paymentOptionsAreAuthorizedBeforeDependencyReadsAndOnlyReturnFilterMetadata() {
        var store = mock(PaymentFilterStore.class);
        var crm = mock(CrmCustomerAreaDisplayClient.class);
        var hr = mock(HrEmployeeDisplayClient.class);
        var catalog = mock(PaymentFilterCatalogClient.class);
        var service = new PaymentFilterService(store, crm, hr, catalog);
        try (var ignored = finance("order:read")) {
            assertThatThrownBy(service::options).isInstanceOf(AuthorizationDeniedException.class);
            verifyNoInteractions(store, crm, hr, catalog);
        }
        when(store.parties(tenant.toString())).thenReturn(new Parties(List.of(new Person("E1","张三","BJ")),List.of("创建人")));
        when(catalog.catalog(any())).thenReturn(new Catalog(List.of(), List.of()));
        try (var ignored = finance("order:payment:read")) {
            assertThat(service.options().employees()).extracting(Person::employeeCode).containsExactly("E1");
            verify(crm).all(argThat(c -> tenant.equals(c.tenantId()) && c.permissions().equals(Set.of("crm:customer:read"))));
            verify(hr).departments(argThat(c -> tenant.equals(c.tenantId()) && c.permissions().equals(Set.of("hr:employee:read"))));
        }
    }
}
