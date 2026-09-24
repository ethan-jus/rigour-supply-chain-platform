package com.rigour.hr.application.service;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrDhbBindingReviewStore;
import com.rigour.shared.context.*;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public final class HrDhbBindingReviewService {
    private final HrDhbBindingReviewStore store;
    public HrDhbBindingReviewService(HrDhbBindingReviewStore store) { this.store=store; }
    public List<DhbBindingReviewView> pending() {
        var caller=caller("hr:employee:read");
        return store.pending(caller.tenantId().toString());
    }
    public void confirm(long bindingId, DhbBindingReviewCommand command) {
        var caller=caller("hr:employee:update");
        if (!"TENANT".equals(caller.principalScope())) throw new AuthorizationDeniedException("人工确认需要用户身份");
        if (bindingId<=0 || command==null || command.expectedVersion()<1
                || command.targetEmployeeId()==null || command.targetEmployeeId()<=0)
            throw new IllegalArgumentException("关联确认参数无效");
        store.confirm(caller.tenantId().toString(), bindingId, command, caller.principalId().toString());
    }
    private static CallerIdentity caller(String permission) {
        var caller=AuthorizationContext.requireCurrent();
        if(caller.tenantId()==null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }
}
