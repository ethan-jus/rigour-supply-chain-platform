package com.rigour.sales.application.service;
import com.rigour.sales.api.v1.model.SalesVisitPolicyModels.*;
import com.rigour.sales.application.port.out.*;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
@Service
public class SalesVisitPolicyService {
    private final SalesVisitPolicyReadRepository policies;
    private final SalesWorkQueryRepository query;
    private final SalesWorkContextService context;
    private final Clock clock;
    public SalesVisitPolicyService(SalesVisitPolicyReadRepository policies,SalesWorkQueryRepository query,SalesWorkContextService context,Clock clock) {
        this.policies=policies;this.query=query;this.context=context;this.clock=clock;
    }
    public List<Version> versions() {
        var caller=SalesWorkContextService.requireTenantCaller();AuthorizationContext.requirePermission("sales:policy:read");
        return policies.versions(caller.tenantId());
    }
    public Snapshot current() {
        var caller=SalesWorkContextService.requireTenantCaller();AuthorizationContext.requirePermission("sales:context:read");
        var identity=context.resolveIdentity(caller,clock.instant());
        var policy=query.findActiveVisitPolicy(caller.tenantId(),identity.profile().id(),identity.profile().cityOrgId(),clock.instant())
            .orElseThrow(()->new BusinessException(ErrorCode.SALES_VISIT_POLICY_NOT_FOUND));
        var snapshot=policies.snapshot(caller.tenantId(),policy.id());
        if(snapshot==null) throw new BusinessException(ErrorCode.SALES_VISIT_POLICY_NOT_FOUND);
        return snapshot;
    }
}
