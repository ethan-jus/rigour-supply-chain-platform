package com.rigour.order.application.service.sales;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.application.port.out.CrmCustomerAreaDisplayClient;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.order.application.port.out.OrderInvoiceStore;
import com.rigour.order.application.port.out.OrderRegisterStore;
import com.rigour.order.application.port.out.OrderRegisterStore.OrderCriteria;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class OrderRegisterServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-1000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-1000-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void ordersFillDepartmentFromHrWhenSnapshotMissing() {
        OrderRegisterStore store = mock(OrderRegisterStore.class);
        CrmCustomerAreaDisplayClient crm = mock(CrmCustomerAreaDisplayClient.class);
        HrEmployeeDisplayClient hr = mock(HrEmployeeDisplayClient.class);
        TestAuthorizationContext.set(caller("order:read"));
        when(store.orders(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new OrderRegisterPage<>(1L, 0, 20, List.of(order(null)), Map.of(), null));
        when(hr.resolve(any(), eq(Set.of("EMP001"))))
                .thenReturn(List.of(new HrEmployeeDisplayClient.EmployeeDisplay(
                        "EMP001", "张三", "ACTIVE", "上海市")));

        OrderRegisterService service = new OrderRegisterService(store, crm, hr, invoiceStore(), resolverProvider());
        var page = service.orders(0, 20, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).departmentName()).isEqualTo("上海市");
    }

    @Test
    void ordersKeepExistingDepartmentWithoutHrLookup() {
        OrderRegisterStore store = mock(OrderRegisterStore.class);
        CrmCustomerAreaDisplayClient crm = mock(CrmCustomerAreaDisplayClient.class);
        HrEmployeeDisplayClient hr = mock(HrEmployeeDisplayClient.class);
        TestAuthorizationContext.set(caller("order:read"));
        when(store.orders(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new OrderRegisterPage<>(1L, 0, 20, List.of(order("成都市")), Map.of(), null));

        OrderRegisterService service = new OrderRegisterService(store, crm, hr, invoiceStore(), resolverProvider());
        var page = service.orders(0, 20, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(page.items().get(0).departmentName()).isEqualTo("成都市");
        verify(hr, never()).resolve(any(), any());
    }

    @Test
    void ordersResolveDepartmentFilterToSnapshotDepartmentScope() {
        OrderRegisterStore store = mock(OrderRegisterStore.class);
        CrmCustomerAreaDisplayClient crm = mock(CrmCustomerAreaDisplayClient.class);
        HrEmployeeDisplayClient hr = mock(HrEmployeeDisplayClient.class);
        TestAuthorizationContext.set(caller("order:read"));
        when(hr.departmentIdsInScope(any(), eq(7L), eq(true))).thenReturn(Set.of(7L, 8L, 9L));
        when(store.orders(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new OrderRegisterPage<>(0L, 0, 20, List.of(), Map.of(), null));

        OrderRegisterService service = new OrderRegisterService(store, crm, hr, invoiceStore(), resolverProvider());
        service.orders(0, 20, null, null, null, null, null, null, 7L, true, null, null, null, null, null, null, null, null, null, null, null, null);

        ArgumentCaptor<OrderCriteria> captor = ArgumentCaptor.forClass(OrderCriteria.class);
        verify(store).orders(eq(TENANT), eq(0), eq(20), captor.capture());
        verify(hr).departmentIdsInScope(any(), eq(7L), eq(true));
        assertThat(captor.getValue().departmentIds()).containsExactlyInAnyOrder(7L, 8L, 9L);
    }

    @Test
    void ordersFailLoudlyWhenDepartmentScopeCannotBeResolved() {
        OrderRegisterStore store = mock(OrderRegisterStore.class);
        CrmCustomerAreaDisplayClient crm = mock(CrmCustomerAreaDisplayClient.class);
        HrEmployeeDisplayClient hr = mock(HrEmployeeDisplayClient.class);
        TestAuthorizationContext.set(caller("order:read"));
        when(hr.departmentIdsInScope(any(), eq(7L), eq(false)))
                .thenThrow(new IllegalStateException("HR unavailable"));

        OrderRegisterService service = new OrderRegisterService(store, crm, hr, invoiceStore(), resolverProvider());

        assertThatThrownBy(
                        () ->
                                service.orders(
                                        0, 20, null, null, null, null, null, null, 7L, false,
                                        null, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        verify(store, never()).orders(any(), anyInt(), anyInt(), any());
    }

    @Test
    void ordersPassValidatedInvoiceStatusFilterToStore() {
        OrderRegisterStore store = mock(OrderRegisterStore.class);
        CrmCustomerAreaDisplayClient crm = mock(CrmCustomerAreaDisplayClient.class);
        HrEmployeeDisplayClient hr = mock(HrEmployeeDisplayClient.class);
        TestAuthorizationContext.set(caller("order:read"));
        when(store.orders(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new OrderRegisterPage<>(0L, 0, 20, List.of(), Map.of(), null));

        OrderRegisterService service = new OrderRegisterService(store, crm, hr, invoiceStore(), resolverProvider());
        service.orders(0, 20, null, null, null, null, null, null, null, null, null, null, null, null, null, "PENDING", null, null, null, null, null, null);
        service.orders(0, 20, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        ArgumentCaptor<OrderCriteria> captor = ArgumentCaptor.forClass(OrderCriteria.class);
        verify(store, org.mockito.Mockito.times(2)).orders(eq(TENANT), eq(0), eq(20), captor.capture());
        assertThat(captor.getAllValues().get(0).invoiceStatusCode()).isEqualTo("PENDING");
        assertThat(captor.getAllValues().get(1).invoiceStatusCode()).isNull();

        // 非法发票状态必须报 400，不能退化成“不筛选”
        assertThatThrownBy(
                        () ->
                                service.orders(
                                        0, 20, null, null, null, null, null, null, null, null,
                                        null, null, null, null, null, "BOGUS", null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, org.mockito.Mockito.times(2)).orders(eq(TENANT), eq(0), eq(20), any());
    }

    private static OrderRegisterOrderView order(String departmentName) {
        return new OrderRegisterOrderView(
                1L, "A001", null, "DINGHUOBAO", "A001", null, null,
                null, null, null,
                null, null,
                "EMP001", "张三",
                null, departmentName,
                null, null, null,
                null, null,
                null, null,
                null, null, null, null, null,
                null, null, null, null, null, null,
                1);
    }

    private static OrderInvoiceStore invoiceStore() {
        OrderInvoiceStore store = mock(OrderInvoiceStore.class);
        when(store.statusesByOrderIds(any(), any())).thenReturn(Map.of());
        return store;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<FundAttachmentUrlResolver> resolverProvider() {
        ObjectProvider<FundAttachmentUrlResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(FundAttachmentUrlResolver.NONE);
        return provider;
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity(
                "TENANT",
                USER_ID,
                TENANT_ID,
                USER_ID,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of("order"),
                Set.of(permission));
    }
}
