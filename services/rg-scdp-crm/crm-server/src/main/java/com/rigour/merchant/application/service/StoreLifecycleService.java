package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.StoreWorkModels.RefreshResult;
import com.rigour.merchant.application.port.out.StoreLifecycleStore;
import com.rigour.merchant.application.port.out.StoreFactStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 仅使用 CRM 本地已确认事实计算归属；以 revision 拒绝过期任务。 */
@Service
public class StoreLifecycleService {
    private static final Logger LOG = LoggerFactory.getLogger(StoreLifecycleService.class);
    private final StoreLifecycleStore store;
    private final StoreFactStore facts;
    private final Clock clock;
    public StoreLifecycleService(StoreLifecycleStore store, StoreFactStore facts, Clock clock) {
        this.store = store; this.facts = facts; this.clock = clock;
    }
    public RefreshResult refresh(String tenant, String storeId) {
        var cycle = store.current(tenant, storeId);
        return cycle == null ? new RefreshResult(null, "PUBLIC", null, null, null) : refresh(cycle);
    }
    private RefreshResult refresh(StoreLifecycleStore.Cycle cycle) {
        var now=clock.instant();
        return store.reconcile(cycle, facts.orders(cycle,now), now);
    }
    public int tick() {
        var cycles = store.pending(100, clock.instant());
        for (var cycle : cycles) {
            try { refresh(cycle); }
            catch (RuntimeException failure) { LOG.error("CRM ownership check failed tenant={} ownership={}", cycle.tenant(), cycle.id(), failure); }
        }
        return cycles.size();
    }
}
