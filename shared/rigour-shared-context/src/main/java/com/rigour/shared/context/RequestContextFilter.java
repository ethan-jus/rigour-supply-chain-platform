package com.rigour.shared.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 在请求入口建立 request/tenant 上下文，并在所有退出路径清理 ThreadLocal。
 * finally 清理是线程池复用下的隔离底线，不能由业务代码负责。
 */
public final class RequestContextFilter extends OncePerRequestFilter {

    static final String DEFAULT_LANGUAGE = "zh-CN";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(RequestHeaders.REQUEST_ID));
        String language = resolveLanguage(request.getHeader(RequestHeaders.ACCEPT_LANGUAGE));
        String tenantId = trimToNull(request.getHeader(RequestHeaders.TENANT_ID));

        RequestContext.set(requestId, language);
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
        }
        response.setHeader(RequestHeaders.REQUEST_ID, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            RequestContext.clear();
        }
    }

    private String resolveRequestId(String requestId) {
        String normalized = trimToNull(requestId);
        return normalized == null ? UUID.randomUUID().toString() : normalized;
    }

    private String resolveLanguage(String language) {
        String normalized = trimToNull(language);
        return normalized == null ? DEFAULT_LANGUAGE : normalized;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
