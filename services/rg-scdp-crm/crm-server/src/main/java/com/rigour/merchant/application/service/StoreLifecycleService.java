package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.StoreWorkModels.RefreshResult;
import com.rigour.merchant.application.port.out.StoreLifecycleStore;
import com.rigour.merchant.application.port.out.StoreOrderClient;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 外部核对在事务外；回写事务以批次和 revision 重新判定，任务可重复执行。 */
@Service
public class StoreLifecycleService {
    private static final Logger LOG = LoggerFactory.getLogger(StoreLifecycleService.class);
    private final StoreLifecycleStore store;
    private final StoreOrderClient orders;
    private final Clock clock;
    private final com.rigour.merchant.application.port.out.StoreVisitCaptureClient captures;
    public StoreLifecycleService(StoreLifecycleStore store, StoreOrderClient orders, Clock clock, com.rigour.merchant.application.port.out.StoreVisitCaptureClient captures) {
        this.store = store; this.orders = orders; this.clock = clock; this.captures=captures;
    }
    public RefreshResult refresh(String tenant, String storeId) {
        var cycle = store.current(tenant, storeId);
        return cycle == null ? new RefreshResult(null, "PUBLIC", null, null, null) : refresh(cycle);
    }
    private RefreshResult refresh(StoreLifecycleStore.Cycle cycle) {
        com.rigour.order.api.v1.model.StoreOrderFacts facts;
        com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Facts evidence=null;
        try {
            if(store.hasCapture(cycle))evidence=captures.facts(cycle.tenant(),cycle.storeId(),cycle.id());
            facts = orders.facts(cycle.tenant(), cycle.storeId(), cycle.id()); }
        catch (RuntimeException failure) {
            LOG.warn("CRM ownership source unavailable tenant={} ownership={} cause={}", cycle.tenant(), cycle.id(), failure.getClass().getSimpleName());
            return store.hold(cycle, clock.instant());
        }
        return store.reconcile(cycle, facts, evidence, clock.instant());
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
