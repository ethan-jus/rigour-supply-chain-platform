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
    private static final Set<String> WAREHOUSE_ACTIONS =
            Set.of(
                    "erp:warehouse:read", "erp:warehouse:create", "erp:warehouse:update",
                    "erp:product:read", "erp:product:write");
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
        return directory(action).visible();
    }

    public List<com.rigour.merchant.api.v1.model.CostCityCheckView> checks(
            String action, List<String> codes) {
        if (action == null) throw new AuthorizationDeniedException("cost-city-reference");
        // 与候选目录相同的功能及数据对象授权；不提升为全局账号查询。
        var directory = directory(action);
        if (!directory.actionAllowed()) throw new AuthorizationDeniedException(action);
        if (codes == null
                || codes.isEmpty()
                || codes.size() > 50
                || codes.stream().anyMatch(c -> c == null || c.isBlank() || c.length() > 64))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "地区编码须为1至50项且每项不超过64字", List.of());
        var configured =
                directory.configured().stream()
                        .map(CostCityView::code)
                        .collect(java.util.stream.Collectors.toSet());
        var visible =
                directory.visible().stream()
                        .map(CostCityView::code)
                        .collect(java.util.stream.Collectors.toSet());
        return codes.stream()
                .distinct()
                .map(
                        code ->
                                new com.rigour.merchant.api.v1.model.CostCityCheckView(
                                        code,
                                        configured.contains(code),
                                        visible.contains(code),
                                        configured.contains(code)
                                                ? null
                                                : directory
                                                        .issues()
                                                        .getOrDefault(
                                                                code,
                                                                "业务归属地区编码（"
                                                                        + code
                                                                        + "）不存在，请在仓库信息中重新选择实际经营城市或总部")))
                .toList();
    }

    private record Directory(
            List<CostCityView> configured,
            List<CostCityView> visible,
            boolean actionAllowed,
            java.util.Map<String, String> issues) {}

    private Directory directory(String action) {
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
            if (!ACTIONS.contains(read) && !WAREHOUSE_ACTIONS.contains(candidate))
                throw new AuthorizationDeniedException(candidate);
            AuthorizationContext.requirePermission(candidate);
            if (!"TENANT".equals(caller.principalScope())) {
                unrestricted = true;
                permittedActions.add(candidate);
                if (!candidate.startsWith("hr:payroll-rule:")) headquarters = true;
                continue;
            }
            var policy = authorization.authorization(caller, candidate);
            if (policy == null) continue;
            if (candidate.startsWith("erp:product:")) {
                if ("ACTIVE".equals(policy.mode())) continue;
                unrestricted = true;
                headquarters = true;
                permittedActions.add(candidate);
                continue;
            }
            if (!"ACTIVE".equals(policy.mode()) || !policy.functionAllowed()) continue;
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
        if (permittedActions.isEmpty())
            return new Directory(List.of(), List.of(), false, java.util.Map.of());
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
        List<com.rigour.merchant.api.v1.model.DictionaryView> allAreas = new ArrayList<>();
        int begin = 0;
        while (true) {
            var page = store.customerAreas(caller.tenantId(), begin, 200, null);
            for (var row : page.items()) {
                allAreas.add(row);
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
        List<CostCityView> configured = new ArrayList<>();
        for (var city : departments) {
            String name = normalized(city.name());
            var matches =
                    areas.stream().filter(row -> name.equals(normalized(row.name()))).toList();
            if (departmentNames.get(name) == 1 && matches.size() == 1) {
                var row = matches.getFirst();
                configured.add(new CostCityView(row.code(), row.name()));
                if (unrestricted || allowed.contains(row.code()))
                    result.add(new CostCityView(row.code(), row.name()));
            }
        }
        if (permittedActions.stream().anyMatch(a -> !a.startsWith("hr:payroll-rule:")))
            configured.add(
                    new CostCityView(
                            HrCostCityReference.HEADQUARTERS_CODE,
                            HrCostCityReference.HEADQUARTERS_NAME));
        if (headquarters)
            result.add(
                    new CostCityView(
                            HrCostCityReference.HEADQUARTERS_CODE,
                            HrCostCityReference.HEADQUARTERS_NAME));
        var issues = new java.util.HashMap<String, String>();
        var activeAreaNames = new java.util.HashMap<String, Integer>();
        areas.forEach(a -> activeAreaNames.merge(normalized(a.name()), 1, Integer::sum));
        for (var area : allAreas) {
            String label = "业务归属地区“" + area.name() + "”（" + area.code() + "）";
            String issue;
            if (Set.of("ABSENT", "DELETED")
                    .contains(java.util.Objects.toString(area.sourcePresence(), "")))
                issue = label + "已在来源系统移除，请核对地区档案并重新维护仓库的业务归属地区";
            else if (!"ACTIVE".equals(area.status())) issue = label + "已停用，请在仓库信息中重新选择启用的经营城市或总部";
            else if (!departmentNames.containsKey(normalized(area.name())))
                issue = label + "不是经营城市，也不是总部；请在仓库信息中将业务归属地区改为实际经营城市或总部";
            else if (departmentNames.get(normalized(area.name())) > 1)
                issue = label + "对应多个同名经营城市部门，无法唯一匹配，请先在部门管理中核对同名经营城市";
            else if (activeAreaNames.getOrDefault(normalized(area.name()), 0) > 1)
                issue = label + "存在多个同名地区，无法唯一匹配经营城市，请先在业务归属地区档案中处理同名数据";
            else continue;
            issues.put(area.code(), issue);
        }
        return new Directory(
                List.copyOf(configured), List.copyOf(result), true, java.util.Map.copyOf(issues));
    }

    private static String normalized(String name) {
        return java.util.Objects.toString(name, "").trim().replaceFirst("市$", "");
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE, "经营城市目录未完整配置，请联系管理员", List.of());
    }
}
