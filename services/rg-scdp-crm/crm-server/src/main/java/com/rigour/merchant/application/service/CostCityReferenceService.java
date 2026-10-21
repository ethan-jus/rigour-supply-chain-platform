package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.CostCityView;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 只开放归属城市的编码与名称，不授予客户查询权限。 */
@Service
public final class CostCityReferenceService {
    private static final Set<String> ACTIONS =
            Set.of(
                    "order:logistics-cost:read",
                    "order:logistics-cost:write",
                    "erp:cost:marketing:read",
                    "erp:cost:marketing:write",
                    "erp:cost:purchase:read",
                    "erp:cost:purchase:write",
                    "erp:cost:loss:read",
                    "erp:cost:loss:write",
                    "erp:cost:rental:read",
                    "erp:cost:rental:write",
                    "hr:expense:read",
                    "hr:expense:write",
                    "hr:payroll-rule:read",
                    "hr:payroll-rule:write");
    private final CrmCustomerQueryStore store;
    private final com.rigour.tenant.iam.client.SupplyAuthorizationClient authorization;

    public CostCityReferenceService(
            CrmCustomerQueryStore store,
            com.rigour.tenant.iam.client.SupplyAuthorizationClient authorization) {
        this.store = store;
        this.authorization = authorization;
    }

    public List<CostCityView> cities(String action) {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null)
            throw new AuthorizationDeniedException("cost-city-reference");
        var actions =
                action == null
                        ? ACTIONS.stream().filter(AuthorizationContext::hasPermission).toList()
                        : List.of(action);
        if (actions.isEmpty()) throw new AuthorizationDeniedException("cost-city-reference");
        Set<String> allowed = new java.util.HashSet<>();
        boolean unrestricted = false;
        for (String candidate : actions) {
            String read = candidate.replaceFirst(":(confirm|void|export)$", ":read");
            if (!ACTIONS.contains(read) && !ACTIONS.contains(candidate))
                throw new AuthorizationDeniedException(candidate);
            AuthorizationContext.requirePermission(candidate);
            if (!"TENANT".equals(caller.principalScope())) {
                unrestricted = true;
                continue;
            }
            var policy = authorization.authorization(caller, candidate);
            if (!"ACTIVE".equals(policy.mode()) || !policy.functionAllowed()) continue;
            String object =
                    candidate.startsWith("hr:") || candidate.startsWith("erp:cost:marketing:")
                            ? "EMPLOYEE"
                            : "INVENTORY";
            var access = com.rigour.tenant.iam.client.ManualCostScope.access(policy, object);
            if (!access.allowed()) continue;
            if (access.cities() == null) unrestricted = true;
            else allowed.addAll(access.cities());
        }
        List<CostCityView> result = new ArrayList<>();
        int begin = 0;
        while (true) {
            var page = store.customerAreas(caller.tenantId(), begin, 200, null);
            for (var row : page.items())
                if ((unrestricted || allowed.contains(row.code()))
                        && "ACTIVE".equals(row.status())
                        && !Set.of("ABSENT", "DELETED")
                                .contains(row.sourcePresence() == null ? "" : row.sourcePresence()))
                    result.add(new CostCityView(row.code(), row.name()));
            begin += page.items().size();
            if (begin >= page.total()) break;
            if (page.items().isEmpty() || begin >= 20000)
                throw new IllegalStateException("城市目录未完整返回");
        }
        return List.copyOf(result);
    }
}
