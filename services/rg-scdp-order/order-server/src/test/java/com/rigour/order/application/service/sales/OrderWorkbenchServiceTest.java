package com.rigour.order.application.service.sales;

import com.rigour.order.application.port.out.OrderWorkbenchStore;
import com.rigour.shared.context.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderWorkbenchServiceTest {
    OrderWorkbenchStore store = mock(OrderWorkbenchStore.class);
    com.rigour.order.application.port.out.EmployeePerformanceTargetClient targets = mock(com.rigour.order.application.port.out.EmployeePerformanceTargetClient.class);
    OrderWorkbenchService service = new OrderWorkbenchService(store, targets);
    UUID tenant = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026,11,1), to = LocalDate.of(2026,11,30);
    void caller(String... permissions) {
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of(permissions)));
    }
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @Test void receiptRequiresReceiptPermissionAndDateWindowIsChinaHalfOpen() {
        caller("order:read");
        assertThatThrownBy(() -> service.details("self","receipts",from,to,0,20)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
        caller("order:payment:read"); service.details("self","receipts",from,to,0,20);
        verify(store).details(tenant.toString(),"self","order:payment:read","receipts",
                Instant.parse("2026-10-31T16:00:00Z"),Instant.parse("2026-11-30T16:00:00Z"),0,20);
    }
    @Test void badRangeAndPagingCannotReachStore() {
        caller("order:read");
        assertThatThrownBy(() -> service.details("self","orders",to,from,0,20)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assertThatThrownBy(() -> service.details("self","orders",from,to,0,1000)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assertThatThrownBy(() -> service.details("","orders",from,to,0,20)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        verifyNoInteractions(store);
    }
    @Test void performanceUsesMonthlyHrTargetAndRejectsAnotherEmployeesTarget() {
        caller("order:payment:read");
        var page = new OrderWorkbenchStore.Totals(2, new java.math.BigDecimal("300"));
        when(store.performanceTotals(anyString(), eq("self"), anyString(), any(), any())).thenReturn(page);
        when(targets.value(tenant.toString(), "self", "2026-11", "2026-11")).thenReturn(new java.math.BigDecimal("1000"));
        var result = service.performance("self", from, to);
        assertThat(result.completedAmount()).isEqualByComparingTo("300");
        assertThat(result.targetAmount()).isEqualByComparingTo("1000");
        verify(store).requireSelf("self", "order:payment:read");
        verify(store, never()).details(anyString(), anyString(), anyString(), anyString(), any(), any(), anyInt(), anyInt());
        when(targets.value(tenant.toString(), "self", "2026-11", "2026-11")).thenReturn(new java.math.BigDecimal("2000"));
        assertThat(service.performance("self", from, to).targetAmount()).isEqualByComparingTo("2000");
        doThrow(new AuthorizationDeniedException("own")).when(store).requireSelf("other", "order:payment:read");
        assertThatThrownBy(() -> service.performance("other", from, to)).isInstanceOf(AuthorizationDeniedException.class);
        verify(targets, never()).value(anyString(), eq("other"), anyString(), anyString());
        caller("order:read");
        assertThatThrownBy(() -> service.performance("self", from, to)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test void summaryUsesDedicatedAggregateAndValidatesBeforeAccess() {
        caller("order:read");
        service.summary(" self ", from, to);
        verify(store).summary(tenant.toString(), "self", "order:read",
                Instant.parse("2026-10-31T16:00:00Z"), Instant.parse("2026-11-30T16:00:00Z"));
        verify(store, never()).details(anyString(), anyString(), anyString(), anyString(), any(), any(), anyInt(), anyInt());
        assertThatThrownBy(() -> service.summary("self", to, from)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        caller();
        assertThatThrownBy(() -> service.summary("self", from, to)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test void customerMetricsUseOrderMenuPermissionAndValidateAllFiltersBeforeQuerying() {
        caller("crm:pool:private-read");
        assertThatThrownBy(() -> service.customerSummary("self",from,to)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.customers("self","NEW",from,to,"",0,20)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.customerOrders("self","C1",0,20)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
        caller("order:read");
        service.customerSummary(" self ",from,to);
        verify(store).customerSummary(eq(tenant.toString()),eq("self"),any(),any());
        service.customers("self","RETURNING",from,to," 台球 ",0,20);
        verify(store).customers(eq(tenant.toString()),eq("self"),eq("RETURNING"),any(),any(),eq("台球"),eq(0),eq(20));
        service.customerOrders("self","C1",0,20);
        verify(store).customerOrders(tenant.toString(),"self","C1",0,20);
        clearInvocations(store);
        assertThatThrownBy(() -> service.customers("self","INVALID",from,to,"",0,20)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assertThatThrownBy(() -> service.customerOrders("self","C1 OR 1=1",0,20)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assertThatThrownBy(() -> service.customerOrders("self","C1",-1,20)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        verifyNoInteractions(store);
    }
}
