package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiEffectiveScopeView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** BI 读取范围只使用当前角色配置，与业务查询共用授权上下文。 */
@Service
public final class BiDataScopeService {
    private final BiDataScopeStore store;

    public BiDataScopeService(BiDataScopeStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public BiEffectiveScopeView effective() {
        actor();
        var current = appPolicy();
        if (current != null) {
            boolean full = full(current);
            var regions =
                    current.clauses().stream()
                            .flatMap(c -> c.regions().references().stream())
                            .distinct()
                            .toList();
            return new BiEffectiveScopeView(
                    full ? "TENANT" : "SCOPED",
                    "AUTHORIZED",
                    null,
                    regions,
                    current.employeeCode(),
                    current.employeeCode(),
                    null,
                    null,
                    full,
                    full ? List.of() : restrictedSubjects(current));
        }
        throw deniedException("bi-supply-context");
    }

    public ScopedSelection resolve(String regionCode, String ownerStaffCode) {
        var actor = actor();
        var current = appPolicy();
        if (current != null)
            return new ScopedSelection(
                    actor.tenantId().toString(),
                    normalized(regionCode, true),
                    normalized(ownerStaffCode, false),
                    full(current));
        throw deniedException("bi-supply-context");
    }

    /** 仅在已经按当前租户读取对象后校验真实归属；缺失归属不会转换为默认范围。 */
    public void requireObjectScope(String objectRegionCode, String objectOwnerStaffCode) {
        if (appPolicy() != null) {
            if (!store.appObjectVisible(
                    actor().tenantId().toString(),
                    objectRegionCode,
                    objectOwnerStaffCode,
                    "analytics:dashboard:read")) throw deniedException("bi-object-scope");
            return;
        }
        throw deniedException("bi-supply-context");
    }

    /** 修改使用该动作的完整授权子句，不能借读取范围取得写权限。 */
    public void requireObjectActionScope(String city, String employee, String action) {
        var context =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.current().orElse(null);
        if (context != null && context.active()) {
            if (!store.appObjectVisible(actor().tenantId().toString(), city, employee, action))
                throw deniedException("bi-object-action-scope");
        } else throw deniedException("bi-supply-context");
    }

    public void requireGlobalGovernance() {
        if (!effective().globalGovernance()) throw deniedException("bi-global-governance");
    }

    /** 受限用户的筛选项直接在 BI 本地事实内收紧，不能事后只过滤全租户城市列表。 */
    public SupplyDashboardFilterOptionsView restrictedFilterOptions() {
        if (appPolicy() != null)
            return store.filterOptions(actor().tenantId().toString(), null, null);
        throw deniedException("bi-supply-context");
    }

    private static com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView appPolicy() {
        return com.rigour.tenant.iam.client.SupplyAuthorizationContext.requireAction(
                "analytics:dashboard:read");
    }

    private static List<String> restrictedSubjects(
            com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView p) {
        var result =
                new java.util.ArrayList<>(
                        List.of(
                                "PROCUREMENT",
                                "RECONCILIATION",
                                "SOURCE_GOVERNANCE",
                                "LEGACY_PERIOD_RECEIPTS"));
        boolean inventory =
                !"NONE".equals(p.warehouseLimit().mode())
                        && p.clauses().stream()
                                .anyMatch(
                                        c ->
                                                "ANALYTICS".equals(c.objectType())
                                                        && List.of("ALL").contains(c.scopeMode())
                                                        && !"NONE".equals(c.warehouses().mode()));
        boolean city =
                !"NONE".equals(p.regionLimit().mode())
                        && p.clauses().stream()
                                .anyMatch(
                                        c ->
                                                "ANALYTICS".equals(c.objectType())
                                                        && List.of("REGION", "DEPARTMENT", "ALL")
                                                                .contains(c.scopeMode())
                                                        && ("DEPARTMENT".equals(c.scopeMode())
                                                                || List.of("NONE", "ALL")
                                                                        .contains(
                                                                                c.departments()
                                                                                        .mode()))
                                                        && List.of("NONE", "ALL")
                                                                .contains(c.warehouses().mode())
                                                        && !"NONE".equals(c.regions().mode()));
        if (!inventory) result.add("INVENTORY");
        if (!city) result.add("CITY_COST");
        return List.copyOf(result);
    }

    private static boolean full(com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView p) {
        return "ALL".equals(p.regionLimit().mode())
                && "ALL".equals(p.warehouseLimit().mode())
                && p.clauses().stream()
                        .anyMatch(
                                c ->
                                        "ANALYTICS".equals(c.objectType())
                                                && "ALL".equals(c.scopeMode())
                                                && List.of("ALL", "NONE")
                                                        .contains(c.departments().mode())
                                                && List.of("ALL", "NONE")
                                                        .contains(c.regions().mode())
                                                && List.of("ALL", "NONE")
                                                        .contains(c.warehouses().mode()));
    }

    private static CallerIdentity actor() {

        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope())
                || actor.tenantId() == null
                || actor.userId() == null) {
            throw deniedException("tenant-user-caller");
        }
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        return actor;
    }

    private static AuthorizationDeniedException deniedException(String reason) {
        return new AuthorizationDeniedException(reason);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalized(String value, boolean uppercase) {
        if (blank(value)) return null;
        return uppercase ? value.strip().toUpperCase(Locale.ROOT) : value.strip();
    }

    /** 控制器之间复用的已验证查询条件；不能由 HTTP 请求绑定生成。 */
    public record ScopedSelection(
            String tenantId, String regionCode, String ownerStaffCode, boolean fullTenant) {}
}
