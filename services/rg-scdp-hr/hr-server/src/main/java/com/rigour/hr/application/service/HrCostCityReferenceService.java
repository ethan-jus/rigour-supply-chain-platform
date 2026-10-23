package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.HrCostCityReference;
import com.rigour.hr.api.v1.model.HrDepartmentView;
import com.rigour.hr.application.port.out.HrOrganizationStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.tenant.iam.client.ManualCostScope;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.springframework.stereotype.Service;

import java.util.*;

/** 延续现有经营城市定义；地区范围在 CRM 映射地区编码后另行取交集。 */
@Service
public final class HrCostCityReferenceService {
    private static final Set<String> ACTIONS =
            Set.of(
                    "order:logistics-cost:read", "order:logistics-cost:write",
                    "erp:cost:marketing:read", "erp:cost:marketing:write",
                    "erp:cost:purchase:read", "erp:cost:purchase:write",
                    "erp:cost:loss:read", "erp:cost:loss:write",
                    "erp:cost:rental:read", "erp:cost:rental:write",
                    "hr:expense:read", "hr:expense:write",
                    "hr:payroll-rule:read", "hr:payroll-rule:write");
    private final HrOrganizationStore store;
    private final SupplyAuthorizationClient authorization;

    public HrCostCityReferenceService(
            HrOrganizationStore store, SupplyAuthorizationClient authorization) {
        this.store = store;
        this.authorization = authorization;
    }

    public List<HrCostCityReference> cities(String action) {
        var caller = AuthorizationContext.requireCurrent();
        String read = action == null ? "" : action.replaceFirst(":(confirm|void|export)$", ":read");
        if (caller.tenantId() == null || !ACTIONS.contains(read))
            throw new AuthorizationDeniedException("cost-city-reference");
        AuthorizationContext.requirePermission(action);
        if ("TENANT".equals(caller.principalScope())) {
            var policy = authorization.authorization(caller, action);
            String object =
                    action.startsWith("hr:") || action.startsWith("erp:cost:marketing:")
                            ? "EMPLOYEE"
                            : "INVENTORY";
            if (policy == null
                    || !"ACTIVE".equals(policy.mode())
                    || !policy.functionAllowed()
                    || !ManualCostScope.access(policy, object).allowed())
                throw new AuthorizationDeniedException(action);
            var scope = ManualCostScope.access(policy, object);
            if (scope.cities() != null && scope.cities().isEmpty()) return List.of();
        }
        var rows = store.departments(caller.tenantId().toString());
        Map<Long, HrDepartmentView> index = new HashMap<>();
        for (var row : rows) {
            if (index.put(row.id(), row) != null) throw new IllegalStateException("经营城市组织目录重复");
        }
        List<HrCostCityReference> result = new ArrayList<>();
        for (var row : rows) {
            if (!"ACTIVE".equals(row.statusCode()) || "销售部".equals(row.departmentName())) continue;
            LinkedList<String> path = new LinkedList<>();
            Set<Long> visited = new HashSet<>();
            var at = row;
            boolean operating = false;
            while (at != null) {
                if (!visited.add(at.id())) throw new IllegalStateException("经营城市组织目录存在循环");
                path.addFirst(at.id().toString());
                if (!row.id().equals(at.id())
                        && "ACTIVE".equals(at.statusCode())
                        && "销售部".equals(at.departmentName())) operating = true;
                at = at.parentId() == null ? null : index.get(at.parentId());
            }
            if (operating)
                result.add(
                        new HrCostCityReference(
                                row.departmentCode(),
                                row.departmentName(),
                                row.id().toString(),
                                path));
        }
        return result.stream().sorted(Comparator.comparing(HrCostCityReference::code)).toList();
    }
}
