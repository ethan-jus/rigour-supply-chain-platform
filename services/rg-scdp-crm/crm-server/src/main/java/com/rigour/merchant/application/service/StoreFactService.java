package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence;
import com.rigour.merchant.api.v1.model.StoreOrderResult;
import com.rigour.merchant.application.port.out.StoreFactStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class StoreFactService {
    private final StoreFactStore store;
    private final Clock clock;
    public StoreFactService(StoreFactStore store,Clock clock) { this.store=store; this.clock=clock; }
    private static void authorize(String tenant,String permission) {
        var caller=AuthorizationContext.requireCurrent();
        if(!"SERVICE".equals(caller.principalScope()) || caller.tenantId()==null || !caller.tenantId().toString().equals(tenant))
            throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
    }
    public void evidence(Evidence e) {
        if(e==null || !id(e.storeId()) || !id(e.ownershipId()) || !id(e.visitId()) || e.tenantId()==null || e.revision()<1 || e.checkedAt()==null || e.checkedInAt()==null || e.captureRef()==null
                || e.employeeCode()==null || e.policyId()==null || e.verifiedPhotoCount()<0
                || e.checkedInAt().isAfter(e.checkedAt()) || e.verifiedRecordingDurationMs()!=null && e.verifiedRecordingDurationMs()<0
                || e.checkedAt().isAfter(clock.instant().plusSeconds(30))
                || e.submittedAt()!=null && (e.submittedAt().isAfter(e.checkedAt()) || e.submittedAt().isBefore(e.checkedInAt()))
                || e.checkedOutAt()!=null && (e.checkedOutAt().isBefore(e.checkedInAt()) || e.checkedOutAt().isAfter(e.checkedAt())))
            throw new RequestValidationException("采集结果参数无效");
        authorize(e.tenantId(),"crm:store:visit-source-write");
        store.acceptEvidence(e,clock.instant());
    }
    private static boolean id(String value){return value!=null && value.matches("[1-9][0-9]{0,18}");}
    public void order(StoreOrderResult r) {
        if(r==null || !id(r.storeId()) || !id(r.ownershipId()) || !id(r.orderId()) || r.sequence()<1 || r.payableAmount()==null || r.observedAt()==null
                || r.storeId()==null || r.ownershipId()==null || r.orderId()==null || r.orderStatus()==null || r.submissionStatus()==null)
            throw new RequestValidationException("订单事实参数无效");
        authorize(r.tenantId(),"crm:store:order-source-write");
        store.acceptOrder(r,clock.instant());
    }
}
