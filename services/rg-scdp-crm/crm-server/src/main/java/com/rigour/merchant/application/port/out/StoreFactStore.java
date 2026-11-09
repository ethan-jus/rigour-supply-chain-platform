package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence;
import com.rigour.merchant.api.v1.model.StoreOrderResult;
import com.rigour.order.api.v1.model.StoreOrderFacts;
import java.time.Instant;
public interface StoreFactStore {
    void acceptEvidence(Evidence evidence,Instant now);
    void acceptOrder(StoreOrderResult result,Instant now);
    StoreOrderFacts orders(StoreLifecycleStore.Cycle cycle,Instant now);
}
