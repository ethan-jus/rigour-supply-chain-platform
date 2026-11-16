package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.CostCustomerView;
import com.rigour.merchant.application.port.out.CostCustomerReferenceStore;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.client.*;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public final class CostCustomerReferenceService {
    private final CostCustomerReferenceStore store;
    private final SupplyAuthorizationClient auth;

    public CostCustomerReferenceService(
            CostCustomerReferenceStore store, SupplyAuthorizationClient auth) {
        this.store = store;
        this.auth = auth;
    }

    public List<CostCustomerView> customers(
            String city, String keyword, Long id, int begin, String action) {
        if (keyword == null || keyword.length() > 200 || begin < 0 || begin > 100000)
            throw new AuthorizationDeniedException(action);
        String tenant = authorize(city, action);
        return store.customers(tenant, city, keyword, id, begin);
    }

    public List<CostCustomerView> customersByCodes(String city, String action, List<String> codes) {
        String tenant = authorize(city, action);
        if (codes == null
                || codes.isEmpty()
                || codes.size() > 100
                || codes.stream().anyMatch(c -> c == null || c.isBlank() || c.length() > 100))
            throw new AuthorizationDeniedException(action);
        return store.customersByCodes(tenant, city, codes.stream().distinct().toList());
    }

    /** 同一请求只解析一次当前动作授权；逐城市检查仍保留，不能用批量绕开范围。 */
    public java.util.Map<String, List<CostCustomerView>> customersByCities(
            String action, java.util.Map<String, List<String>> requested) {
        if (requested == null
                || requested.isEmpty()
                || requested.size() > 50
                || requested.values().stream().anyMatch(v -> v == null || v.isEmpty())
                || requested.values().stream().mapToInt(List::size).sum() > 200)
            throw new AuthorizationDeniedException(action);
        var caller = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(action);
        var policy = policy(action);
        var normalized = new java.util.LinkedHashMap<String, List<String>>();
        // 复用请求中已经解析的授权上下文；跨城市的代码集合不相互混用。
        for (var entry : requested.entrySet()) {
            var codes = entry.getValue();
            if (codes.stream().anyMatch(c -> c == null || c.isBlank() || c.length() > 100))
                throw new AuthorizationDeniedException(action);
            authorize(entry.getKey(), action, policy);
            normalized.put(entry.getKey(), codes.stream().distinct().toList());
        }
        return store.customersByCities(
                caller.tenantId().toString(), java.util.Collections.unmodifiableMap(normalized));
    }

    private String authorize(String city, String action) {
        AuthorizationContext.requirePermission(action);
        return authorize(city, action, policy(action));
    }

    private com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView policy(String action) {
        if (!Set.of(
                        "order:logistics-cost:read",
                        "order:logistics-cost:write",
                        "order:logistics-cost:confirm",
                        "order:logistics-cost:export")
                .contains(action)) throw new AuthorizationDeniedException(action);
        var caller = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(caller.principalScope())) return null;
        var p =
                SupplyAuthorizationContext.current().isPresent()
                        ? SupplyAuthorizationContext.requireAction(action)
                        : auth.authorization(caller, action);
        if (!"ACTIVE".equals(p.mode()) || !p.functionAllowed())
            throw new AuthorizationDeniedException(action);
        return p;
    }

    private String authorize(
            String city,
            String action,
            com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView policy) {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null || city == null || city.isBlank() || city.length() > 64)
            throw new AuthorizationDeniedException(action);
        if (policy != null) ManualCostScope.require(policy, "INVENTORY", city, action);
        return caller.tenantId().toString();
    }
}
