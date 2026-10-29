package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.domain.store.OwnershipProtection.Policy;
import com.rigour.order.api.v1.model.StoreOrderFacts;
import java.time.Instant;
import java.util.List;

public interface StoreLifecycleStore {
    record Cycle(String tenant, String storeId, String id, String employeeId, long revision,
            Instant startedAt, Instant deadline, Policy policy) {}
    boolean hasCapture(Cycle cycle);
    List<Cycle> pending(int limit, Instant now);
    Cycle current(String tenant, String storeId);
    RefreshResult reconcile(Cycle expected, StoreOrderFacts facts, com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Facts captures, Instant now);
    RefreshResult hold(Cycle expected, Instant now);
}
