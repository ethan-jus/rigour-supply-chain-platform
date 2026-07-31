package com.rigour.shared.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 在请求入口建立 request/tenant 上下文，并在所有退出路径清理 ThreadLocal。
 * finally 清理是线程池复用下的隔离底线，不能由业务代码负责。
 */
public final class RequestContextFilter extends OncePerRequestFilter {

    static final String DEFAULT_LANGUAGE = "zh-CN";
    private final TrustedContextSigner contextSigner;

    public RequestContextFilter(TrustedContextSigner contextSigner) {
        this.contextSigner = contextSigner;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(RequestHeaders.REQUEST_ID));
        String language = resolveLanguage(request.getHeader(RequestHeaders.ACCEPT_LANGUAGE));
        RequestContext.set(requestId, language);
        response.setHeader(RequestHeaders.REQUEST_ID, requestId);

        try {
            if (contextSigner.hasContextHeaders(request)) {
                if (!contextSigner.verify(request)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid trusted request context");
                    return;
                }
                CallerIdentity identity;
                try { identity = callerIdentity(request); }
                catch (IllegalArgumentException exception) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid trusted request context");
                    return;
                }
                AuthorizationContext.set(identity);
                if (identity.tenantId() != null) TenantContext.setTenantId(identity.tenantId().toString());
            }
            filterChain.doFilter(request, response);
        } finally {
            AuthorizationContext.clear();
            TenantContext.clear();
            RequestContext.clear();
        }
    }

    private CallerIdentity callerIdentity(HttpServletRequest request) {
        String scope = required(request, RequestHeaders.PRINCIPAL_SCOPE);
        UUID principalId = uuid(request, RequestHeaders.PRINCIPAL_ID, true);
        UUID tenantId = uuid(request, RequestHeaders.TENANT_ID, false);
        UUID userId = uuid(request, RequestHeaders.USER_ID, false);
        UUID platformUserId = uuid(request, RequestHeaders.PLATFORM_USER_ID, false);
        UUID sessionId = uuid(request, RequestHeaders.SESSION_ID, true);
        long sessionVersion = version(request, RequestHeaders.SESSION_VERSION, true);
        long securityVersion = version(request, RequestHeaders.USER_SECURITY_VERSION, true);
        long tenantPolicyVersion = version(request, RequestHeaders.TENANT_POLICY_VERSION, false);
        return new CallerIdentity(scope, principalId, tenantId, userId, platformUserId, sessionId,
                sessionVersion, securityVersion, tenantPolicyVersion,
                csv(request.getHeader(RequestHeaders.ROLES)), csv(request.getHeader(RequestHeaders.PERMISSIONS)));
    }

    private UUID uuid(HttpServletRequest request, String name, boolean required) {
        String value = trimToNull(request.getHeader(name));
        if (value == null) {
            if (required) throw new IllegalArgumentException(name + " is required");
            return null;
        }
        return UUID.fromString(value);
    }

    private long version(HttpServletRequest request, String name, boolean required) {
        String value = trimToNull(request.getHeader(name));
        if (value == null) {
            if (required) throw new IllegalArgumentException(name + " is required");
            return 0;
        }
        long result = Long.parseLong(value);
        if (result < 0) throw new IllegalArgumentException(name + " is invalid");
        return result;
    }

    private String required(HttpServletRequest request, String name) {
        String value = trimToNull(request.getHeader(name));
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private Set<String> csv(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return Set.of();
        return Arrays.stream(normalized.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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
