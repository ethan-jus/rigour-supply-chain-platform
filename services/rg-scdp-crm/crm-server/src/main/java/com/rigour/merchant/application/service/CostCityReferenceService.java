package com.rigour.merchant.application.service;

import com.rigour.hr.api.v1.model.HrCostCityReference;
import com.rigour.merchant.api.v1.model.CostCityView;
import com.rigour.merchant.application.port.out.CostOperatingCitySource;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

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
    private final CostOperatingCitySource operatingCities;

    public CostCityReferenceService(
            CrmCustomerQueryStore store,
            com.rigour.tenant.iam.client.SupplyAuthorizationClient authorization,
            CostOperatingCitySource operatingCities) {
        this.store = store;
        this.authorization = authorization;
        this.operatingCities = operatingCities;
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
        boolean headquarters = false;
        List<String> permittedActions = new ArrayList<>();
        for (String candidate : actions) {
            String read = candidate.replaceFirst(":(confirm|void|export)$", ":read");
            if (!ACTIONS.contains(read) && !ACTIONS.contains(candidate))
                throw new AuthorizationDeniedException(candidate);
            AuthorizationContext.requirePermission(candidate);
            if (!"TENANT".equals(caller.principalScope())) {
                unrestricted = true;
                permittedActions.add(candidate);
                if (!candidate.startsWith("hr:payroll-rule:")) headquarters = true;
                continue;
            }
            var policy = authorization.authorization(caller, candidate);
            if (policy == null || !"ACTIVE".equals(policy.mode()) || !policy.functionAllowed())
                continue;
            String object =
                    candidate.startsWith("hr:") || candidate.startsWith("erp:cost:marketing:")
                            ? "EMPLOYEE"
                            : "INVENTORY";
            var access = com.rigour.tenant.iam.client.ManualCostScope.access(policy, object);
            if (!access.allowed() || access.cities() != null && access.cities().isEmpty()) continue;
            permittedActions.add(candidate);
            if (access.cities() == null) unrestricted = true;
            else allowed.addAll(access.cities());
            if (!candidate.startsWith("hr:payroll-rule:")
                    && (access.cities() == null
                            || access.cities().contains(HrCostCityReference.HEADQUARTERS_CODE)))
                headquarters = true;
        }
        if (permittedActions.isEmpty()) return List.of();
        var departments = operatingCities.cities(permittedActions.getFirst());
        if (departments == null || departments.isEmpty()) throw unavailable();
        var departmentNames = new java.util.HashMap<String, Integer>();
        var departmentCodes = new java.util.HashSet<String>();
        for (var city : departments) {
            if (city == null
                    || city.code() == null
                    || city.code().isBlank()
                    || city.name() == null
                    || city.name().isBlank()
                    || !departmentCodes.add(city.code())
                    || HrCostCityReference.HEADQUARTERS_CODE.equals(city.code()))
                throw unavailable();
            departmentNames.merge(normalized(city.name()), 1, Integer::sum);
        }
        List<com.rigour.merchant.api.v1.model.DictionaryView> areas = new ArrayList<>();
        int begin = 0;
        while (true) {
            var page = store.customerAreas(caller.tenantId(), begin, 200, null);
            for (var row : page.items()) {
                if (HrCostCityReference.HEADQUARTERS_CODE.equals(row.code())) throw unavailable();
                if ("ACTIVE".equals(row.status())
                        && !Set.of("ABSENT", "DELETED")
                                .contains(row.sourcePresence() == null ? "" : row.sourcePresence()))
                    areas.add(row);
            }
            begin += page.items().size();
            if (begin >= page.total()) break;
            if (page.items().isEmpty() || begin >= 20000)
                throw new IllegalStateException("城市目录未完整返回");
        }
        List<CostCityView> result = new ArrayList<>();
        for (var city : departments) {
            String name = normalized(city.name());
            var matches =
                    areas.stream().filter(row -> name.equals(normalized(row.name()))).toList();
            if (departmentNames.get(name) == 1 && matches.size() == 1) {
                var row = matches.getFirst();
                if (unrestricted || allowed.contains(row.code()))
                    result.add(new CostCityView(row.code(), row.name()));
            }
        }
        if (headquarters)
            result.add(
                    new CostCityView(
                            HrCostCityReference.HEADQUARTERS_CODE,
                            HrCostCityReference.HEADQUARTERS_NAME));
        return List.copyOf(result);
    }

    private static String normalized(String name) {
        return java.util.Objects.toString(name, "").trim().replaceFirst("市$", "");
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE, "经营城市目录未完整配置，请联系管理员", List.of());
    }
}
