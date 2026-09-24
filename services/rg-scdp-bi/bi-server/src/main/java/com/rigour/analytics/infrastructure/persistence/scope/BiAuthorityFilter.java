package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.tenant.iam.client.SupplyAuthorizationContext;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 受限查询和写操作先核验归属；全部数据查询直接读取租户内已发布的 BI 数据。 */
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
                // 读取的 ALL 范围不依赖部门、主责人或拜访投影；写操作仍按实际动作校验归属。
                boolean allDataRead =
                        "GET".equals(request.getMethod())
                                && BiScopePredicates.unrestricted(
                                        SupplyAuthorizationContext.requireAction(
                                                "analytics:dashboard:read"));
                if (!allDataRead) {
                    projector.refresh(AuthorizationContext.requireCurrent().tenantId());
                    people.refresh(AuthorizationContext.requireCurrent().tenantId());
                }
            } catch (AuthorizationDeniedException e) {
                response.sendError(403, "没有看板访问权限");
                return;
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("BI 权威归属投影核验失败", e);
                response.sendError(503, "归属数据暂不可核验，请刷新后重试");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
