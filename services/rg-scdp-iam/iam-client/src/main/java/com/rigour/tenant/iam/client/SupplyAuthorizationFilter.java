package com.rigour.tenant.iam.client;

import com.rigour.shared.context.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 每个供应链域服务独立在线核验，直连后端同样生效；SERVICE 的具体用途由专用领域接口校验。 */
public final class SupplyAuthorizationFilter extends OncePerRequestFilter {
    private final SupplyAuthorizationClient client;

    public SupplyAuthorizationFilter(SupplyAuthorizationClient client) {
        this.client = client;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var caller = AuthorizationContext.current().orElse(null);
        if (caller == null || !"TENANT".equals(caller.principalScope())) {
            chain.doFilter(request, response);
            return;
        }
        com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView initial;
        try {
            initial = client.authorization(caller, "supply:application:access");
        } catch (RestClientResponseException e) {
            response.sendError(
                    e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403
                            ? 403
                            : 503,
                    "供应链访问资格暂不可用");
            return;
        } catch (RuntimeException e) {
            response.sendError(503, "供应链授权服务暂不可用");
            return;
        }
        try (var state = SupplyAuthorizationContext.open(client, caller, initial)) {
            if (state.active()) {
                try (var permissions =
                        AuthorizationContext.useVerifiedApplication(
                                "SUPPLY_CHAIN", initial.permissions())) {
                    chain.doFilter(request, response);
                }
            } else chain.doFilter(request, response);
        }
    }
}
