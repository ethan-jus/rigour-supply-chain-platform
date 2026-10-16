package com.rigour.erp.application.service.cost;

import com.rigour.erp.api.v1.model.ErpCostSourceView;
import com.rigour.erp.application.port.out.ErpCostStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public final class ErpLogisticsReferenceService {
    private final ErpCostStore store;

    public ErpLogisticsReferenceService(ErpCostStore store) {
        this.store = store;
    }

    public String supplier(Long id, String action) {
        if (!Set.of(
                        "order:logistics-cost:read",
                        "order:logistics-cost:write",
                        "order:logistics-cost:confirm")
                .contains(action)) throw new AuthorizationDeniedException(action);
        var a = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(action);
        if (a.tenantId() == null || id == null) throw new AuthorizationDeniedException(action);
        return store
                .references(a.tenantId().toString(), "suppliers", "", id, null, 0, action)
                .stream()
                .findFirst()
                .orElseThrow(() -> ErpCostService.bad("供应商不存在或无权限"))
                .name();
    }

    public String warehouse(Long id, String action) {
        if (!Set.of(
                        "order:logistics-cost:read",
                        "order:logistics-cost:write",
                        "order:logistics-cost:confirm",
                        "order:logistics-cost:void")
                .contains(action)) throw new AuthorizationDeniedException(action);
        var a = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(action);
        if (a.tenantId() == null || id == null) throw new AuthorizationDeniedException(action);
        return store.warehouse(a.tenantId().toString(), id, action);
    }

    public List<ErpCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action) {
        return sources(leg, keyword, ids, action, null, 0);
    }

    public List<ErpCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action, String city, int begin) {
        if (!Set.of(
                        "order:logistics-cost:read",
                        "order:logistics-cost:write",
                        "order:logistics-cost:confirm",
                        "order:logistics-cost:void")
                .contains(action))
            throw new AuthorizationDeniedException("logistics-reference-action");
        var a = AuthorizationContext.requireCurrent();
        if (a.tenantId() == null) throw new AuthorizationDeniedException(action);
        AuthorizationContext.requirePermission(action);
        if (!Set.of("PURCHASE", "TRANSFER", "DELIVERY").contains(leg)
                || ids != null && ids.size() > 100) throw ErpCostService.bad("物流来源参数无效");
        return store.sources(
                a.tenantId().toString(),
                leg,
                keyword,
                ids == null ? List.of() : ids,
                action,
                city,
                Math.max(0, begin));
    }

    public com.rigour.erp.api.v1.model.ErpCostOptionView warehouseDetail(Long id, String action) {
        warehouse(id, action);
        return store.warehouseOption(
                AuthorizationContext.requireCurrent().tenantId().toString(), id, action);
    }
}
