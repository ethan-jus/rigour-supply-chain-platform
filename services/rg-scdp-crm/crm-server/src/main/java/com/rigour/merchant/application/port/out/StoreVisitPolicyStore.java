package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy;
import com.rigour.merchant.application.service.StoreScope;
import java.time.Instant;
public interface StoreVisitPolicyStore {
    Policy active(StoreScope scope,String storeId,Instant now);
}
