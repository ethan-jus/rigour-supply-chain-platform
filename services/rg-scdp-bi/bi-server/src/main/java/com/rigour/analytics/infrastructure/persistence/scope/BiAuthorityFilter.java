package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.shared.context.AuthorizationContext;
import com.rigour.tenant.iam.client.SupplyAuthorizationContext;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 新权限的 BI 请求先核验来源归属版本，防止当前客户移交后继续使用旧主责投影。 */
public final class BiAuthorityFilter extends OncePerRequestFilter {
    private final BiAuthorityProjector projector;
    private final BiPeopleProjector people;

    public BiAuthorityFilter(BiAuthorityProjector projector, BiPeopleProjector people) {
        this.people = people;
        this.projector = projector;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SupplyAuthorizationContext.current()
                .map(SupplyAuthorizationContext::active)
                .orElse(false)) {
            try {
                projector.refresh(AuthorizationContext.requireCurrent().tenantId());
                people.refresh(AuthorizationContext.requireCurrent().tenantId());
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("BI 权威归属投影核验失败", e);
                response.sendError(503, "归属数据暂不可核验，请刷新后重试");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
