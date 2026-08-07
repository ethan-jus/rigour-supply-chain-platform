package com.rigour.gateway.security;

import com.rigour.gateway.config.GatewaySecurityProperties;
import com.rigour.shared.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

/** 向IAM在线确认会话、安全版本和租户策略版本；开启时失败关闭。 */
public final class CurrentTokenValidationFilter extends OncePerRequestFilter {
    public static final String PUBLIC_FEISHU_JSAPI_SIGN_PATH = "/api/v1/platform/feishu/jsapi-sign";
    public static final String PUBLIC_FEISHU_AUTH_EXCHANGE_PATH = "/api/v1/auth/feishu/exchange";
    private static final Logger log = LoggerFactory.getLogger(CurrentTokenValidationFilter.class);
    private final RestClient restClient;
    private final GatewaySecurityProperties properties;

    public CurrentTokenValidationFilter(RestClient restClient, GatewaySecurityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (isPublicFeishuBootstrap(request)) {
            log.info("飞书初始化请求通过 Gateway 公共入口 requestId={} method={} path={}",
                    RequestContext.getRequestId(), request.getMethod(), request.getRequestURI());
            return true;
        }
        return !properties.isCurrentTokenValidationEnabled()
                || request.getRequestURI().startsWith("/actuator/health");
    }

    private static boolean isPublicFeishuBootstrap(HttpServletRequest request) {
        return (PUBLIC_FEISHU_JSAPI_SIGN_PATH.equals(request.getRequestURI())
                || PUBLIC_FEISHU_AUTH_EXCHANGE_PATH.equals(request.getRequestURI()))
                && "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            log.warn("当前请求缺少有效登录身份 requestId={} path={}",
                    RequestContext.getRequestId(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        long startedAt = System.nanoTime();
        log.debug("开始向IAM校验当前会话 requestId={} path={}",
                RequestContext.getRequestId(), request.getRequestURI());
        try {
            CurrentTokenSnapshot snapshot = restClient.get().uri(properties.requireIamCurrentTokenUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getToken().getTokenValue())
                    .retrieve().body(CurrentTokenSnapshot.class);
            if (snapshot == null) {
                log.warn("IAM返回空的会话校验结果 requestId={} path={} elapsedMs={}",
                        RequestContext.getRequestId(), request.getRequestURI(), elapsedMillis(startedAt));
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "IAM returned an empty token snapshot");
                return;
            }
            request.setAttribute(CurrentTokenValidationFilter.class.getName() + ".roles", snapshot.roles());
            request.setAttribute(CurrentTokenValidationFilter.class.getName() + ".permissions", snapshot.permissions());
            log.debug("IAM当前会话校验通过 requestId={} path={} rolesCount={} permissionsCount={} elapsedMs={}",
                    RequestContext.getRequestId(), request.getRequestURI(), snapshot.roles().size(),
                    snapshot.permissions().size(), elapsedMillis(startedAt));
            filterChain.doFilter(request, response);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (exception.getStatusCode().value() == HttpServletResponse.SC_UNAUTHORIZED
                    || exception.getStatusCode().value() == HttpServletResponse.SC_FORBIDDEN) {
                log.warn("IAM拒绝当前会话 requestId={} path={} iamStatus={} elapsedMs={}",
                        RequestContext.getRequestId(), request.getRequestURI(), status, elapsedMillis(startedAt));
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "IAM rejected the current access token");
                return;
            }
            log.warn("IAM会话校验返回异常 requestId={} path={} iamStatus={} elapsedMs={}",
                    RequestContext.getRequestId(), request.getRequestURI(), status, elapsedMillis(startedAt));
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "IAM current-token validation is temporarily unavailable");
        } catch (RestClientException exception) {
            log.warn("IAM会话校验服务不可用 requestId={} path={} elapsedMs={} reason={}",
                    RequestContext.getRequestId(), request.getRequestURI(), elapsedMillis(startedAt),
                    exception.getMessage());
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "IAM current-token validation is temporarily unavailable");
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public static String rolesAttribute() { return CurrentTokenValidationFilter.class.getName() + ".roles"; }
    public static String permissionsAttribute() { return CurrentTokenValidationFilter.class.getName() + ".permissions"; }

    public record CurrentTokenSnapshot(Set<String> roles, Set<String> permissions) {
        public CurrentTokenSnapshot {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }
}
