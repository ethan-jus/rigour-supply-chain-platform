package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiEffectiveScopeView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 统一收紧 BI 读取范围。签名身份、精确员工关联、IAM 范围投影缺一则拒绝。 */
@Service
public final class BiDataScopeService {
    private static final List<String> RESTRICTED_SUBJECTS =
            List.of(
                    "INVENTORY",
                    "PROCUREMENT",
                    "CITY_COST",
                    "RECONCILIATION",
                    "SOURCE_GOVERNANCE",
                    "LEGACY_PERIOD_RECEIPTS");
    private static final List<String> CITY_RESTRICTED_SUBJECTS =
            List.of("INVENTORY", "PROCUREMENT", "RECONCILIATION", "SOURCE_GOVERNANCE");
    private final BiDataScopeStore store;
    private final Clock clock;
    private final BiDataScopeRenewer renewer;

    public BiDataScopeService(
            BiDataScopeStore store, Clock analyticsClock, BiDataScopeRenewer renewer) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(analyticsClock);
        this.renewer = Objects.requireNonNull(renewer);
    }

    public BiEffectiveScopeView effective() {
        CallerIdentity actor = actor();
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
        if (actor.roles().contains("TENANT_SUPER_ADMIN")) {
            return new BiEffectiveScopeView(
                    "TENANT",
                    "AUTHORIZED",
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    true,
                    List.of());
        }
        var identity =
                store.identity(actor.tenantId().toString(), actor.userId().toString()).orElse(null);
        if (identity == null)
            return denied("IDENTITY_UNAVAILABLE", "账号尚未关联可验证的 HR 员工和 CRM 销售，请联系管理员");
        if (blank(identity.employeeCode())
                || blank(identity.ownerStaffCode())
                || blank(identity.iamBindingRef())
                || blank(identity.hrEmployeeRef())
                || blank(identity.crmEmployeeRef())) {
            return denied("IDENTITY_INCOMPLETE", "员工与销售身份关联不完整，暂不能读取经营数据");
        }
        if (identity.verifiedAt() == null
                || identity.expiresAt() == null
                || identity.verifiedAt().isAfter(clock.instant())
                || identity.userSecurityVersion() != actor.userSecurityVersion()
                || identity.tenantPolicyVersion() != actor.tenantPolicyVersion()) {
            return denied("SCOPE_STALE", "身份或授权范围已失效，需要重新同步授权投影");
        }
        var sourceGrants = store.grants(actor.tenantId().toString(), actor.userId().toString());
        if (!identity.verifiedAt().plus(java.time.Duration.ofMinutes(15)).isAfter(clock.instant())
                || !identity.expiresAt().isAfter(clock.instant())) {
            identity = renewer.renew(actor, identity, sourceGrants).orElse(null);
            if (identity == null) return denied("SOURCE_REVALIDATION_FAILED", "身份或范围重验未通过，已限制数据访问");
        }
        var grants =
                sourceGrants.stream()
                        .filter(grant -> actor.roles().contains(grant.roleCode()))
                        .toList();
        if (grants.isEmpty()) return denied("SCOPE_UNAVAILABLE", "尚未配置可验证的 BI 数据范围");
        if (grants.stream()
                .anyMatch(
                        grant ->
                                blank(grant.iamPolicyRef())
                                        || blank(grant.regionCode())
                                        || !List.of("SELF", "MY_CITY", "MY_REGION")
                                                .contains(grant.scopeType()))) {
            return denied("SCOPE_UNSUPPORTED", "当前授权策略缺少可执行的城市或员工范围");
        }
        // 混合本人/城市授权不扩大本人城市范围：仅使用具有城市权限的城市集合。
        boolean cityScope = grants.stream().anyMatch(grant -> !"SELF".equals(grant.scopeType()));
        var regions =
                grants.stream()
                        .filter(grant -> !cityScope || !"SELF".equals(grant.scopeType()))
                        .map(BiDataScopeStore.Grant::regionCode)
                        .distinct()
                        .sorted()
                        .toList();
        String owner = cityScope ? null : identity.ownerStaffCode();
        return new BiEffectiveScopeView(
                cityScope ? "CITY" : "SELF",
                "AUTHORIZED",
                null,
                regions,
                identity.employeeCode(),
                identity.ownerStaffCode(),
                regions.getFirst(),
                owner,
                false,
                cityScope ? CITY_RESTRICTED_SUBJECTS : RESTRICTED_SUBJECTS);
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
        var scope = effective();
        requireAccessible(scope);
        String region = normalized(regionCode, true);
        String owner = normalized(ownerStaffCode, false);
        if (scope.globalGovernance())
            return new ScopedSelection(actor.tenantId().toString(), region, owner, true);
        if (region == null) region = scope.defaultRegionCode();
        if (!scope.regionCodes().contains(region)) throw deniedException("bi-city-scope");
        if ("SELF".equals(scope.accessLevel())) {
            if (owner != null && !owner.equals(scope.ownerStaffCode()))
                throw deniedException("bi-owner-scope");
            owner = scope.ownerStaffCode();
        }
        return new ScopedSelection(actor.tenantId().toString(), region, owner, false);
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
        var scope = effective();
        requireAccessible(scope);
        boolean allowed =
                scope.globalGovernance()
                        || (!blank(objectRegionCode)
                                && scope.regionCodes().contains(objectRegionCode)
                                && (!"SELF".equals(scope.accessLevel())
                                        || scope.ownerStaffCode().equals(objectOwnerStaffCode)));
        var observationContext =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.current().orElse(null);
        if (observationContext != null && !observationContext.active())
            store.compareObject(
                    actor().tenantId().toString(),
                    objectRegionCode,
                    objectOwnerStaffCode,
                    "analytics:dashboard:read",
                    allowed);
        if (!allowed) throw deniedException("bi-object-scope");
    }

    /** 修改使用该动作的完整授权子句，不能借读取范围取得写权限。 */
    public void requireObjectActionScope(String city, String employee, String action) {
        var context =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.current().orElse(null);
        if (context != null && context.active()) {
            if (!store.appObjectVisible(actor().tenantId().toString(), city, employee, action))
                throw deniedException("bi-object-action-scope");
        } else requireObjectScope(city, employee);
    }

    public void requireGlobalGovernance() {
        if (!effective().globalGovernance()) throw deniedException("bi-global-governance");
    }

    /** 受限用户的筛选项直接在 BI 本地事实内收紧，不能事后只过滤全租户城市列表。 */
    public SupplyDashboardFilterOptionsView restrictedFilterOptions() {
        if (appPolicy() != null)
            return store.filterOptions(actor().tenantId().toString(), null, null);
        var scope = effective();
        requireAccessible(scope);
        if (scope.globalGovernance())
            throw new IllegalStateException("Use tenant filter options for tenant administrators");
        return store.filterOptions(
                actor().tenantId().toString(),
                scope.regionCodes(),
                "SELF".equals(scope.accessLevel()) ? scope.ownerStaffCode() : null);
    }

    private static com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView appPolicy() {
        var context =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.current().orElse(null);
        return context != null && context.active()
                ? com.rigour.tenant.iam.client.SupplyAuthorizationContext.requireAction(
                        "analytics:dashboard:read")
                : null;
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
                                                        && List.of("WAREHOUSE", "ALL")
                                                                .contains(c.scopeMode())
                                                        && List.of("NONE", "ALL")
                                                                .contains(c.departments().mode())
                                                        && List.of("NONE", "ALL")
                                                                .contains(c.regions().mode())
                                                        && !"NONE".equals(c.warehouses().mode()));
        boolean city =
                !"NONE".equals(p.regionLimit().mode())
                        && p.clauses().stream()
                                .anyMatch(
                                        c ->
                                                "ANALYTICS".equals(c.objectType())
                                                        && List.of("REGION", "ALL")
                                                                .contains(c.scopeMode())
                                                        && List.of("NONE", "ALL")
                                                                .contains(c.departments().mode())
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
        com.rigour.tenant.iam.client.SupplyAuthorizationContext.observe(
                "analytics:dashboard:read", "analytics:dashboard:read");
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope())
                || actor.tenantId() == null
                || actor.userId() == null) {
            throw deniedException("tenant-user-caller");
        }
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        return actor;
    }

    private static BiEffectiveScopeView denied(String code, String reason) {
        return new BiEffectiveScopeView(
                "DENIED",
                code,
                reason,
                List.of(),
                null,
                null,
                null,
                null,
                false,
                RESTRICTED_SUBJECTS);
    }

    private static void requireAccessible(BiEffectiveScopeView scope) {
        if ("DENIED".equals(scope.accessLevel())) throw deniedException(scope.reasonCode());
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
