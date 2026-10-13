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

    public List<ErpCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action) {
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
                a.tenantId().toString(), leg, keyword, ids == null ? List.of() : ids, action);
    }
}
