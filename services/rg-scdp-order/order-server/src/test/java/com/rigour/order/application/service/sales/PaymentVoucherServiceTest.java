package com.rigour.order.application.service.sales;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import com.rigour.order.application.port.out.PaymentVoucherStore;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.ObjectProvider;

class PaymentVoucherServiceTest {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @SuppressWarnings("unchecked")
    @Test void requiresReadPermissionAndValidatesExactInputBeforeQuery() {
        var store = mock(PaymentVoucherStore.class);
        ObjectProvider<FundAttachmentUrlResolver> urls = mock(ObjectProvider.class);
        when(urls.getIfAvailable(any())).thenReturn(FundAttachmentUrlResolver.NONE);
        var service = new PaymentVoucherService(store, urls);
        TestAuthorizationContext.set(caller("unrelated"));
        assertThatThrownBy(() -> service.checkTransaction("TXN")).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
        TestAuthorizationContext.set(caller("order:read"));
        assertThatThrownBy(() -> service.checkTransaction(" ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.checkTransaction("x".repeat(129))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.vouchers(0)).isInstanceOf(BusinessException.class);
        service.checkTransaction(" TXN ");
        verify(store).transactionMatches(TENANT_ID.toString(), "TXN");
    }
    @SuppressWarnings("unchecked")
    @Test void displaysUnrecognizedImagesAndDoesNotReturnRemovedEvidence() {
        var store=mock(PaymentVoucherStore.class);
        ObjectProvider<FundAttachmentUrlResolver> urls=mock(ObjectProvider.class);
        when(urls.getIfAvailable(any())).thenReturn(FundAttachmentUrlResolver.NONE);
        when(store.attachmentKeys(TENANT_ID.toString(),1)).thenReturn(List.of("current"));
        when(store.vouchers(TENANT_ID.toString(),1)).thenReturn(List.of(new com.rigour.order.api.v1.model.PaymentVoucherModels.VoucherTransaction("removed",null,"OLD","",null)));
        TestAuthorizationContext.set(caller("order:read"));
        var rows=new PaymentVoucherService(store,urls).vouchers(1);
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(rows.get(0).voucherKey()).isEqualTo("current");
        org.assertj.core.api.Assertions.assertThat(rows.get(0).transactionNo()).isNull();
    }
    private static CallerIdentity caller(String permission) {
        return new CallerIdentity(
                "TENANT", USER_ID, TENANT_ID, USER_ID, null, UUID.randomUUID(), 0, 0, 0,
                Set.of("order"), Set.of(permission));
    }
}
