package com.rigour.order.application.service.sales;
import com.rigour.order.api.v1.model.PaymentVoucherAuditModels.*;
import com.rigour.order.application.port.out.PaymentVoucherAuditStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PaymentVoucherAuditServiceTest {
    final UUID tenant=UUID.randomUUID(),user=UUID.randomUUID();
    void caller(String... permissions){TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of(permissions)));}
    @AfterEach void clear(){TestAuthorizationContext.clear();}
    @Test void reviewRequiresFinancePermissionCurrentFingerprintAndWriteScope() {
        var store=mock(PaymentVoucherAuditStore.class);var service=new PaymentVoucherAuditService(store, mock(com.rigour.order.application.port.out.OrderStatusDictionary.class));
        caller("order:read");
        assertThatThrownBy(()->service.review(new ReviewRequest("g","f","NEED_EVIDENCE","note"))).isInstanceOf(AuthorizationDeniedException.class);
        var p=new Payment("1","P1","1","SO1","C","S",BigDecimal.TEN,null,"CHECKED",false,"T",List.of("a"),List.of(new Evidence("a",BigDecimal.TEN,"T","")));
        var group=PaymentVoucherAuditEngine.scan(List.of(p),Map.of()).groups().get(0);
        when(store.payments(tenant.toString(),"order:read")).thenReturn(List.of(p));
        when(store.payments(tenant.toString(),"order:payment:check")).thenReturn(List.of(p));
        caller("order:read","order:payment:check");
        assertThatThrownBy(()->service.review(new ReviewRequest(group.key(),"old","NORMAL_COMBINED","依据"))).isInstanceOf(BusinessException.class).hasMessageContaining("变化");
        when(store.payments(tenant.toString(),"order:payment:check")).thenReturn(List.of());
        assertThatThrownBy(()->service.review(new ReviewRequest(group.key(),group.fingerprint(),"NORMAL_COMBINED","依据"))).isInstanceOf(AuthorizationDeniedException.class);
        verify(store,never()).appendReview(any(),any(),any(),any(),any(),any(),any());
        when(store.payments(tenant.toString(),"order:payment:check")).thenReturn(List.of(p));
        service.review(new ReviewRequest(group.key(),group.fingerprint(),"NORMAL_COMBINED"," 已查原图 "));
        verify(store).appendReview(tenant.toString(),group.key(),group.fingerprint(),"NORMAL_COMBINED","已查原图",user.toString(),List.of("1"),List.of());
    }
    @Test void statesUseAllAuthorizedMonthsAndDoNotExposeUnrequestedOrExcludedPayments() {
        var store = mock(PaymentVoucherAuditStore.class);
        var service = new PaymentVoucherAuditService(store, mock(com.rigour.order.application.port.out.OrderStatusDictionary.class));
        caller("order:read");
        var a = new Payment("1","P1","1","SO1","C","S",new BigDecimal("150"),java.time.Instant.parse("2026-08-01T00:00:00Z"),"CHECKED",false,"T",List.of("a"),List.of(new Evidence("a",new BigDecimal("300"),"T","",java.time.Instant.parse("2026-08-01T00:00:00Z"),"SECOND",null,null,null)));
        var b = new Payment("2","P2","2","SO2","C","S",new BigDecimal("150"),java.time.Instant.parse("2026-09-01T00:00:00Z"),"RECEIVED",false,"T",List.of("a"),List.of(new Evidence("a",new BigDecimal("300"),"T","",java.time.Instant.parse("2026-08-01T00:00:00Z"),"SECOND",null,null,null)));
        when(store.payments(tenant.toString(),"order:read")).thenReturn(List.of(a,b));
        when(store.reviews(tenant.toString())).thenReturn(Map.of());
        assertThat(service.paymentStates(List.of("2","unauthorized")))
                .containsOnlyKeys("2").containsEntry("2",new PaymentAuditState("NORMAL",List.of(),List.of(new VoucherTime(java.time.Instant.parse("2026-08-01T00:00:00Z"),"SECOND"))));
        assertThatThrownBy(() -> service.paymentStates(Collections.nCopies(201,"1"))).isInstanceOf(BusinessException.class);
        assertThat(service.paymentStates(List.of())).isEmpty();
    }

    @Test void reviewValidatesActiveTenantReasonsAndRequiresReasonForAbnormal() {
        var store = mock(PaymentVoucherAuditStore.class);
        var dict = mock(com.rigour.order.application.port.out.OrderStatusDictionary.class);
        var service = new PaymentVoucherAuditService(store,dict);
        caller("order:read","order:payment:check");
        when(dict.activeCodes(tenant.toString(),"PAYMENT_VOUCHER_AUDIT_REASON")).thenReturn(Set.of("BANK_FEE"));
        var p = new Payment("1","P1","1","SO1","C","S",BigDecimal.TEN,null,"CHECKED",false,null,List.of(),List.of());
        when(store.payments(tenant.toString(),"order:read")).thenReturn(List.of(p));
        when(store.payments(tenant.toString(),"order:payment:check")).thenReturn(List.of(p));
        var g = PaymentVoucherAuditEngine.scan(List.of(p),Map.of()).groups().getFirst();
        assertThatThrownBy(() -> service.review(new ReviewRequest(g.key(),g.fingerprint(),"ABNORMAL","依据",List.of()))).hasMessageContaining("原因");
        assertThatThrownBy(() -> service.review(new ReviewRequest(g.key(),g.fingerprint(),"ABNORMAL","依据",List.of("DISABLED")))).hasMessageContaining("原因");
        service.review(new ReviewRequest(g.key(),g.fingerprint(),"PENDING_REVIEW","待补手续费依据",List.of("BANK_FEE")));
        verify(store).appendReview(tenant.toString(),g.key(),g.fingerprint(),"PENDING_REVIEW","待补手续费依据",user.toString(),List.of("1"),List.of("BANK_FEE"));
    }

}
