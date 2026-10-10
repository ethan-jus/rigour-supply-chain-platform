package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.PaymentVoucherAuditModels.*;
import com.rigour.order.application.port.out.PaymentVoucherAuditStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentVoucherAuditService {
    private final PaymentVoucherAuditStore store;
    public PaymentVoucherAuditService(PaymentVoucherAuditStore store) {this.store=store;}
    private String tenant(String permission) {
        var c=AuthorizationContext.requireCurrent();
        if(c.tenantId()==null)throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission); return c.tenantId().toString();
    }
    @Transactional(readOnly=true) public Scan scan() {
        String t=tenant("order:read");
        return PaymentVoucherAuditEngine.scan(store.payments(t,"order:read"),store.reviews(t));
    }
    @Transactional public Review review(ReviewRequest request) {
        String t=tenant("order:payment:check"); tenant("order:read");
        if(request==null || request.groupKey()==null || request.fingerprint()==null || request.conclusion()==null ||
                !Set.of("NORMAL_COMBINED","CONFIRMED_DUPLICATE","NEED_EVIDENCE").contains(request.conclusion()) ||
                request.note()==null || request.note().isBlank() || request.note().length()>1000)
            throw new BusinessException(ErrorCode.BAD_REQUEST,"请选择结论并填写不超过1000字的核查说明",List.of());
        var group=PaymentVoucherAuditEngine.scan(store.payments(t,"order:read"),Map.of()).groups().stream()
            .filter(g->g.key().equals(request.groupKey())).findFirst()
            .orElseThrow(()->new BusinessException(ErrorCode.BAD_REQUEST,"核查组不存在或无权查看，请重新扫描",List.of()));
        var writable=new HashSet<>(store.payments(t,"order:payment:check").stream().map(Payment::id).toList());
        if(!writable.containsAll(group.payments().stream().map(Payment::id).toList())) throw new AuthorizationDeniedException("order:payment:check");
        if(!group.fingerprint().equals(request.fingerprint()))
            throw new BusinessException(ErrorCode.CONFLICT,"回款或凭证已变化，请重新扫描后核查",List.of());
        return store.appendReview(t,group.key(),group.fingerprint(),request.conclusion(),request.note().trim(),
            AuthorizationContext.requireCurrent().principalId().toString(),group.payments().stream().map(Payment::id).toList());
    }
}
