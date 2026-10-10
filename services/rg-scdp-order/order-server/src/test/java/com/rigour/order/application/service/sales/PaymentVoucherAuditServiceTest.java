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
        var store=mock(PaymentVoucherAuditStore.class);var service=new PaymentVoucherAuditService(store);
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
        verify(store).appendReview(tenant.toString(),group.key(),group.fingerprint(),"NORMAL_COMBINED","已查原图",user.toString(),List.of("1"));
    }
}
