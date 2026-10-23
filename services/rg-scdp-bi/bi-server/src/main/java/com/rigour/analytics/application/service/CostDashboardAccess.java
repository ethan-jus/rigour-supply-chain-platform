package com.rigour.analytics.application.service;

import com.rigour.analytics.domain.cost.CostFact;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 看板范围与原业务范围取交集；每次请求重新取得在线授权，不把历史快照当成访问凭证。 */
@Component
public final class CostDashboardAccess {
    public static final String READ = "analytics:cost-dashboard:read",
            EXPORT = "analytics:cost-dashboard:export";
    public static final Map<String, String> ACTIONS =
            Map.of(
                    "freight",
                    "order:logistics-cost:read",
                    "warehouse",
                    "erp:cost:rental:read",
                    "office",
                    "erp:cost:rental:read",
                    "premises_unknown",
                    "erp:cost:rental:read",
                    "marketing",
                    "erp:cost:marketing:read",
                    "expense",
                    "hr:expense:read",
                    "labor",
                    "hr:payroll:read",
                    "purchase",
                    "erp:cost:purchase:read",
                    "loss",
                    "erp:cost:loss:read");
    private final ObjectMapper json;
    private final ThreadPoolExecutor policyReads =
            new ThreadPoolExecutor(
                    8,
                    8,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128),
                    task -> {
                        var thread = new Thread(task, "cost-policy-read");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    @jakarta.annotation.PreDestroy
    public void close() {
        policyReads.shutdownNow();
    }

    public CostDashboardAccess(ObjectMapper json) {
        this.json = json;
    }

    public record Access(
            CallerIdentity actor,
            SupplyAuthorizationView dashboard,
            Map<String, SupplyAuthorizationView> sources,
            SupplyAuthorizationView exportPolicy,
            String hash) {
        public boolean exportAllowed() {
            return exportPolicy != null && exportPolicy.functionAllowed();
        }

        public boolean exportVisible(CostFact r) {
            return exportAllowed() && visible(r) && dashboardVisible(exportPolicy, r);
        }

        public Set<String> categories() {
            Set<String> result = new HashSet<>(sources.keySet());
            if (sources.containsKey("expense"))
                result.addAll(Set.of("office", "warehouse", "freight", "marketing", "purchase"));
            return Set.copyOf(result);
        }

        public boolean visible(CostFact r) {
            return dashboardVisible(dashboard, r)
                    && sourceVisible(
                            sources.get(
                                    "HR".equals(r.source())
                                            ? ("PAYROLL".equals(r.sourceCategory())
                                                    ? "labor"
                                                    : "expense")
                                            : r.category()),
                            r,
                            actor.principalId().toString());
        }
    }

    public Access current() {
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope())
                || actor.tenantId() == null
                || actor.userId() == null) throw new AuthorizationDeniedException(READ);
        AuthorizationContext.requirePermission(READ);
        // 在线批读同一主体/版本的全部策略；旧自定义客户端仍使用有界并发兼容路径。
        Set<String> requested = new LinkedHashSet<>(ACTIONS.values());
        requested.add(READ);
        requested.add(EXPORT);
        requested.removeIf(
                action -> {
                    try {
                        AuthorizationContext.requirePermission(action);
                        return false;
                    } catch (AuthorizationDeniedException denied) {
                        return true;
                    }
                });
        try {
            SupplyAuthorizationContext.current()
                    .orElseThrow()
                    .prefetchActions(requested, policyReads, Duration.ofSeconds(10));
        } finally {
            policyReads.purge();
        }
        var dashboard = SupplyAuthorizationContext.requireAction(READ);
        Map<String, SupplyAuthorizationView> policies = new TreeMap<>();
        for (var e : ACTIONS.entrySet())
            try {
                AuthorizationContext.requirePermission(e.getValue());
                policies.put(e.getKey(), SupplyAuthorizationContext.requireAction(e.getValue()));
            } catch (AuthorizationDeniedException denied) {
                /* 显式排除未授权业务范围，不吞掉网络或版本错误。 */
            }
        SupplyAuthorizationView export = null;
        try {
            AuthorizationContext.requirePermission(EXPORT);
            export = SupplyAuthorizationContext.requireAction(EXPORT);
        } catch (AuthorizationDeniedException denied) {
            /* 独立导出权限。 */
        }
        String raw =
                json.writeValueAsString(
                        List.of(
                                dashboard,
                                policies,
                                actor.principalId(),
                                actor.sessionVersion(),
                                actor.tenantPolicyVersion(),
                                Optional.ofNullable(export)));
        try {
            return new Access(
                    actor,
                    dashboard,
                    Map.copyOf(policies),
                    export,
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(raw.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean sourceVisible(SupplyAuthorizationView p, CostFact r, String actor) {
        if (p == null || !p.functionAllowed()) return false;
        if ("HR".equals(r.source())) {
            var scope = ManualCostScope.access(p, "EMPLOYEE");
            if (!scope.allowed()
                    || scope.cities() != null
                            && !scope.cities().contains(r.city())
                            && (r.rawCity() == null || !scope.cities().contains(r.rawCity())))
                return false;
            return employee(p, r.employeeCode(), r.departmentId(), r.departmentPath());
        }
        String object = "marketing".equals(r.category()) ? "EMPLOYEE" : "INVENTORY";
        var manual = ManualCostScope.access(p, object);
        if (!manual.allowed()
                || manual.cities() != null
                        && (r.rawCity() == null || !manual.cities().contains(r.rawCity())))
            return false;
        if ("marketing".equals(r.category())) {
            if (r.employeeCode() == null || r.employeeCode().isBlank())
                return manual.allCreators() || actor.equals(r.createdBy());
            var current = r.dimensions().get("authorizationEmployeeDepartment");
            if (current == null) return false;
            List<String> path =
                    Arrays.stream(
                                    current.label()
                                            .replace("[", "")
                                            .replace("]", "")
                                            .replace("\"", "")
                                            .split(","))
                            .map(String::trim)
                            .toList();
            return employee(p, r.employeeCode(), current.key(), path);
        }
        if (r.warehouseId() == null && r.sourceWarehouseId() == null)
            return manual.allCreators() || actor.equals(r.createdBy());
        if (r.warehouseId() == null || r.sourceWarehouseId() == null) return false;
        return includes(p.warehouseLimit(), r.warehouseId())
                && includes(p.warehouseLimit(), r.sourceWarehouseId())
                && p.clauses().stream()
                        .anyMatch(
                                c ->
                                        "INVENTORY".equals(c.objectType())
                                                && Set.of("ALL", "WAREHOUSE")
                                                        .contains(c.scopeMode())
                                                && unlimited(c.departments())
                                                && unlimited(c.regions())
                                                && includes(c.warehouses(), r.warehouseId())
                                                && includes(c.warehouses(), r.sourceWarehouseId()));
    }

    private static boolean employee(
            SupplyAuthorizationView p, String employee, String department, List<String> path) {
        return p.clauses().stream()
                .anyMatch(
                        c ->
                                "EMPLOYEE".equals(c.objectType())
                                        && unlimited(c.regions())
                                        && unlimited(c.warehouses())
                                        && switch (c.scopeMode()) {
                                            case "SELF" ->
                                                    p.employeeCode() != null
                                                            && p.employeeCode().equals(employee);
                                            case "DEPARTMENT" ->
                                                    !"NONE".equals(c.departments().mode());
                                            case "ALL" -> true;
                                            default -> false;
                                        }
                                        && ("NONE".equals(c.departments().mode())
                                                || department(
                                                        c.departments(),
                                                        department,
                                                        path,
                                                        c.includeDescendants())));
    }

    public static boolean dashboardVisible(SupplyAuthorizationView p, CostFact r) {
        if (!p.functionAllowed() || !region(p.regionLimit(), r)) return false;
        return p.clauses().stream()
                .anyMatch(
                        c -> {
                            if (!"ANALYTICS".equals(c.objectType())) return false;
                            boolean scope =
                                    switch (c.scopeMode()) {
                                        case "ALL" -> true;
                                        case "REGION" -> !"NONE".equals(c.regions().mode());
                                        case "DEPARTMENT" -> !"NONE".equals(c.departments().mode());
                                        case "SELF" ->
                                                (p.employeeCode() != null
                                                        && p.employeeCode()
                                                                .equals(r.employeeCode()))
                                                        || (p.userId() != null
                                                                && p.userId().toString()
                                                                        .equals(r.createdBy()));
                                        case "WAREHOUSE" -> !"NONE".equals(c.warehouses().mode());
                                        default -> false;
                                    };
                            if (!scope) return false;
                            if (!"NONE".equals(c.regions().mode()) && !region(c.regions(), r))
                                return false;
                            if (!"NONE".equals(c.departments().mode())
                                    && !department(
                                            c.departments(),
                                            r.dashboardDepartment(),
                                            r.dashboardDepartmentPath(),
                                            c.includeDescendants())) return false;
                            if (!unlimited(c.warehouses())
                                    && (!includes(c.warehouses(), r.warehouseId())
                                            || !includes(c.warehouses(), r.sourceWarehouseId())
                                            || !includes(p.warehouseLimit(), r.warehouseId())
                                            || !includes(
                                                    p.warehouseLimit(), r.sourceWarehouseId())))
                                return false;
                            return true;
                        });
    }

    private static boolean department(Limit l, String id, List<String> path, boolean children) {
        return "ALL".equals(l.mode())
                || "SPECIFIED".equals(l.mode())
                        && (id != null && l.references().contains(id)
                                || children && !Collections.disjoint(l.references(), path));
    }

    private static boolean region(Limit l, CostFact r) {
        return "ALL".equals(l.mode())
                || "SPECIFIED".equals(l.mode())
                        && (l.references().contains(r.city())
                                || r.rawCity() != null && l.references().contains(r.rawCity()));
    }

    private static boolean includes(Limit l, String id) {
        return "ALL".equals(l.mode())
                || "SPECIFIED".equals(l.mode()) && id != null && l.references().contains(id);
    }

    private static boolean unlimited(Limit l) {
        return Set.of("ALL", "NONE").contains(l.mode());
    }
}
