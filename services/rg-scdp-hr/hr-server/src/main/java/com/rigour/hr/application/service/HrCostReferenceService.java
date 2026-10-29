package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.HrCostEmployeeView;
import com.rigour.hr.application.port.out.HrCostReferenceStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public final class HrCostReferenceService {
    private final HrCostReferenceStore store;

    public HrCostReferenceService(HrCostReferenceStore store) {
        this.store = store;
    }

    public List<HrCostEmployeeView> employees(String action) {
        boolean costAction =
                action != null
                        && Set.of("read", "write", "confirm", "void", "export").stream()
                                .anyMatch(
                                        operation ->
                                                Set.of(
                                                                "erp:cost:purchase:",
                                                                "erp:cost:loss:",
                                                                "erp:cost:marketing:",
                                                                "erp:cost:rental:",
                                                                "order:logistics-cost:")
                                                        .stream()
                                                        .anyMatch(
                                                                prefix ->
                                                                        action.equals(
                                                                                prefix
                                                                                        + operation)));
        if (!costAction) throw new AuthorizationDeniedException("cost-reference-action");
        var a = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(action);
        if (a.tenantId() == null || !"TENANT".equals(a.principalScope()))
            throw new AuthorizationDeniedException(action);
        return store.employees(a.tenantId().toString(), action);
    }
}
