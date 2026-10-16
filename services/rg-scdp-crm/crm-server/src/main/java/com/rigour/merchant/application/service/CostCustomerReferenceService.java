package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.CostCustomerView;
import com.rigour.merchant.application.port.out.CostCustomerReferenceStore;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.client.*;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public final class CostCustomerReferenceService {
    private final CostCustomerReferenceStore store;
    private final SupplyAuthorizationClient auth;

    public CostCustomerReferenceService(
            CostCustomerReferenceStore store, SupplyAuthorizationClient auth) {
        this.store = store;
        this.auth = auth;
    }

    public List<CostCustomerView> customers(
            String city, String keyword, Long id, int begin, String action) {
        if (!Set.of(
                        "order:logistics-cost:read",
                        "order:logistics-cost:write",
                        "order:logistics-cost:confirm")
                .contains(action)) throw new AuthorizationDeniedException(action);
        var caller = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(action);
        if (caller.tenantId() == null
                || city == null
                || city.isBlank()
                || city.length() > 64
                || keyword.length() > 200) throw new AuthorizationDeniedException(action);
        if ("TENANT".equals(caller.principalScope())) {
            var policy =
                    SupplyAuthorizationContext.current().isPresent()
                            ? SupplyAuthorizationContext.requireAction(action)
                            : auth.authorization(caller, action);
            if (!"ACTIVE".equals(policy.mode()) || !policy.functionAllowed())
                throw new AuthorizationDeniedException(action);
            ManualCostScope.require(policy, "INVENTORY", city, action);
        }
        return store.customers(caller.tenantId().toString(), city, keyword, id, Math.max(0, begin));
    }
}
