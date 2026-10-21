package com.rigour.tenant.iam.client;

import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import java.util.*;

/** 仅解释授权快照；业务表名、SQL 与记录归属校验由各领域持久层负责。 */
public final class ManualCostScope {
    private ManualCostScope() {}

    /** cities 为 null 表示不限地区，空集合表示没有授权地区。 */
    public record Access(boolean allCreators, List<String> cities, boolean allowed) {
        public Access {
            if (cities != null) cities = List.copyOf(cities);
        }
    }

    public static Access access(SupplyAuthorizationView p, String object) {
        if (p == null) return new Access(true, null, true);
        var clauses =
                p.clauses().stream()
                        .filter(c -> object.equals(c.objectType()) && !"NONE".equals(c.scopeMode()))
                        .toList();
        if (clauses.isEmpty()) return new Access(false, List.of(), false);
        boolean all =
                clauses.stream()
                                .anyMatch(
                                        c ->
                                                "ALL".equals(c.scopeMode())
                                                        && unrestricted(c.departments())
                                                        && unrestricted(c.regions())
                                                        && unrestricted(c.warehouses()))
                        && ("EMPLOYEE".equals(object) || "ALL".equals(p.warehouseLimit().mode()));
        Set<String> regions = new TreeSet<>();
        for (var c : clauses) {
            if (unrestricted(c.regions())) {
                regions = null;
                break;
            }
            if ("SPECIFIED".equals(c.regions().mode())) regions.addAll(c.regions().references());
        }
        if (!unrestricted(p.regionLimit())) {
            Set<String> member =
                    new TreeSet<>(
                            "SPECIFIED".equals(p.regionLimit().mode())
                                    ? p.regionLimit().references()
                                    : List.of());
            if (regions == null) regions = member;
            else regions.retainAll(member);
        }
        return new Access(all, regions == null ? null : List.copyOf(regions), true);
    }

    private static boolean unrestricted(SupplyAuthorizationView.Limit limit) {
        return limit != null && Set.of("NONE", "ALL").contains(limit.mode());
    }

    public static void require(
            SupplyAuthorizationView p, String object, String city, String action) {
        var access = access(p, object);
        if (!access.allowed() || access.cities() != null && !access.cities().contains(city))
            throw new AuthorizationDeniedException(action);
    }
}
