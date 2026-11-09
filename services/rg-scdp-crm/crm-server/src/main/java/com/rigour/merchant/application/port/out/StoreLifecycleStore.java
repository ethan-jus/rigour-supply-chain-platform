package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.domain.store.OwnershipProtection.Policy;
import com.rigour.order.api.v1.model.StoreOrderFacts;
import java.time.Instant;
import java.util.List;

public interface StoreLifecycleStore {
    record Cycle(String tenant, String storeId, String id, String employeeId, long revision,
            Instant startedAt, Instant deadline, Policy policy) {}
    List<Cycle> pending(int limit, Instant now);
    Cycle current(String tenant, String storeId);
    RefreshResult reconcile(Cycle expected, StoreOrderFacts facts, Instant now);
    RefreshResult hold(Cycle expected, Instant now);
}
