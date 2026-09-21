package com.rigour.order.application.service.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PaymentCheckCommand;
import com.rigour.order.application.port.out.CrmCustomerAreaDisplayClient;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.order.application.port.out.OrderInvoiceStore;
import com.rigour.order.application.port.out.OrderRegisterStore;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 财务核对回款：权限、流水号必填、仓储调用与结果回传。 */
class OrderRegisterPaymentCheckServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d8e");
    private static final UUID USER_ID = UUID.fromString("019fa000-0000-7000-8000-000000000002");

    private final OrderRegisterStore store = mock(OrderRegisterStore.class);

    private OrderRegisterService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FundAttachmentUrlResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(FundAttachmentUrlResolver.NONE);
        return new OrderRegisterService(
                store,
                mock(CrmCustomerAreaDisplayClient.class),
                mock(HrEmployeeDisplayClient.class),
                mock(OrderInvoiceStore.class),
                provider);
    }

    @Test
    void checkPaymentRequiresCheckPermission() {
        TestAuthorizationContext.set(caller("order:read"));

        assertThatThrownBy(() -> service().checkPayment(9L, new PaymentCheckCommand("TXN-1", 2)))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).checkPayment(any(), anyLong(), any(), anyInt(), any(), any());
    }

    @Test
    void checkPaymentRequiresTransactionNo() {
        TestAuthorizationContext.set(caller("order:payment:check"));

        assertThatThrownBy(() -> service().checkPayment(9L, new PaymentCheckCommand("  ", 2)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).checkPayment(any(), anyLong(), any(), anyInt(), any(), any());
    }

    @Test
    void checkPaymentDelegatesToStoreAndReturnsCheckedView() {
        OrderRegisterPaymentView checked =
                new OrderRegisterPaymentView(
                        9L, "PAY-1", "PAY-1", 1L, "SO-1", null, 2L, "C-1", "客户",
                        "HZ", null, "EMP-1", "张三", null, null, Instant.parse("2026-09-01T00:00:00Z"),
                        null, null, "RECEIVED", Instant.parse("2026-09-12T00:00:00Z"), "TXN-1",
                        List.of(), List.of(), null, null, null, null, null, null,
                        USER_ID.toString(), Instant.parse("2026-09-21T03:00:00Z"), 2);
        when(store.checkPayment(
                        eq(TENANT_ID.toString()), eq(9L), eq("TXN-1"), eq(2), eq(USER_ID.toString()), any()))
                .thenReturn(checked);
        TestAuthorizationContext.set(caller("order:payment:check"));

        OrderRegisterPaymentView result = service().checkPayment(9L, new PaymentCheckCommand("TXN-1", 2));

        assertThat(result).isSameAs(checked);
        verify(store)
                .checkPayment(
                        eq(TENANT_ID.toString()),
                        eq(9L),
                        eq("TXN-1"),
                        eq(2),
                        eq(USER_ID.toString()),
                        any());
    }

    @Test
    void checkPaymentRequiresRevisionForOptimisticLock() {
        TestAuthorizationContext.set(caller("order:payment:check"));

        assertThatThrownBy(() -> service().checkPayment(9L, new PaymentCheckCommand("TXN-1", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> service().checkPayment(9L, new PaymentCheckCommand("TXN-1", 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).checkPayment(any(), anyLong(), any(), anyInt(), any(), any());
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity(
                "TENANT", USER_ID, TENANT_ID, USER_ID, null, UUID.randomUUID(), 0, 0, 0,
                Set.of("order"), Set.of(permission));
    }
}
