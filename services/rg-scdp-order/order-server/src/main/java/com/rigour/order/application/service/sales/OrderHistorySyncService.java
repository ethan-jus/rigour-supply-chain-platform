package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.HistorySyncModels.*;
import com.rigour.order.application.port.out.OrderHistorySyncStore;
import com.rigour.shared.context.*;

import org.springframework.stereotype.Service;

/** 同步来源写入仅允许可信服务身份，人工核对另需同步管理权限。 */
@Service
public class OrderHistorySyncService {
    private final OrderHistorySyncStore store;

    public OrderHistorySyncService(OrderHistorySyncStore store) {
        this.store = store;
    }

    private CallerIdentity actor(boolean write, boolean source) {
        var a = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(
                source ? "order:write" : write ? "integration:dhb:write" : "integration:dhb:read");
        if (source && !"SERVICE".equals(String.valueOf(a.principalScope())))
            throw new AuthorizationDeniedException("service-caller-required");
        return a;
    }

    public StoreView overview(Long id) {
        var a = actor(false, false);
        return store.overview(a.tenantId().toString(), id);
    }

    public Intake sourceOrder(SourceOrder c) {
        var a = actor(true, true);
        return store.sourceOrder(a.tenantId().toString(), c);
    }

    public Intake cancelSourceOrder(CancelSourceOrder c) {
        var a = actor(true, true);
        return store.cancelSourceOrder(a.tenantId().toString(), a.principalId().toString(), c);
    }

    public void confirmNew(NewOrder c) {
        var a = actor(true, false);
        store.confirmNew(a.tenantId().toString(), a.principalId().toString(), c);
    }

    /** 业务明确批准的历史补单，与切换日后的普通新单确认分开。 */
    public void confirmHistoricalNew(NewOrder c) {
        var a = actor(true, false);
        store.confirmHistoricalNew(a.tenantId().toString(), a.principalId().toString(), c);
    }

    public void deleteUnlinkedHistory(DeleteUnlinkedHistory c) {
        var a = actor(true, false);
        AuthorizationContext.requirePermission("order:delete");
        store.deleteUnlinkedHistory(a.tenantId().toString(), a.principalId().toString(), c);
    }

    public java.util.Map<String, Long> normalizeGroup(NormalizeGroup c) {
        var a = actor(true, true);
        AuthorizationContext.requirePermission("integration:dhb:write");
        AuthorizationContext.requirePermission("order:delete");
        return store.normalizeGroup(a.tenantId().toString(), a.principalId().toString(), c);
    }

    public String bind(Bind c) {
        var a = actor(true, false);
        return store.bind(a.tenantId().toString(), a.principalId().toString(), c);
    }

    public Intake receipt(Receipt c) {
        var a = actor(true, true);
        return store.receipt(a.tenantId().toString(), c);
    }

    public void allocate(Allocate c) {
        var a = actor(true, false);
        store.allocate(a.tenantId().toString(), a.principalId().toString(), c);
    }

    public void allocateProducts(AllocateProducts c) {
        var a = actor(true, false);
        store.allocateProducts(a.tenantId().toString(), a.principalId().toString(), c);
    }

    public Performance performance(String month) {
        var a = actor(false, false);
        return store.performance(a.tenantId().toString(), month);
    }

    public void confirmOwner(OwnerReview c) {
        var a = actor(true, false);
        store.confirmOwner(a.tenantId().toString(), a.principalId().toString(), c);
    }
}
