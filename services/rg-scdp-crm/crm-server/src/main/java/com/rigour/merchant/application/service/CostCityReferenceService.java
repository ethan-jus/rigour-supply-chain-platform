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
                    "erp:cost:rental:read",
                    "erp:cost:rental:write",
                    "hr:expense:read",
                    "hr:expense:write",
                    "hr:payroll-rule:read",
                    "hr:payroll-rule:write");
    private final CrmCustomerQueryStore store;

    public CostCityReferenceService(CrmCustomerQueryStore store) {
        this.store = store;
    }

    public List<CostCityView> cities() {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null
                || ACTIONS.stream().noneMatch(AuthorizationContext::hasPermission))
            throw new AuthorizationDeniedException("cost-city-reference");
        List<CostCityView> result = new ArrayList<>();
        int begin = 0;
        while (true) {
            var page = store.customerAreas(caller.tenantId(), begin, 200, null);
            for (var row : page.items())
                if ("ACTIVE".equals(row.status())
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
