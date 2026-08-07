package com.rigour.order.infrastructure.logging;

import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 订单中心接口访问日志。
 *
 * <p>记录方法、路径、查询参数、JSON请求体、租户、requestId、响应状态和耗时，便于定位接口问题；
 * token、sKey、签名、密码等凭据只记录参数名并将值脱敏。</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class OrderCenterApiAccessLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(OrderCenterApiAccessLogFilter.class);
    private static final int MAX_BODY_LENGTH = 4096;
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:token|password|secret|skey|signature|authorization|credential)\\\"\\s*:\\s*)(\\\"(?:\\\\.|[^\\\"])*\\\"|[^,}\\s]+)");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = request instanceof ContentCachingRequestWrapper cached
                ? cached : new ContentCachingRequestWrapper(request, 1_000_000);
        long startedAt = System.nanoTime();
        String outcome = "SUCCESS";
        try {
            filterChain.doFilter(wrapped, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            outcome = "ERROR:" + exception.getClass().getSimpleName();
            throw exception;
        } finally {
            log.info("订单中心接口调用 requestId={} tenantId={} method={} path={} params={} body={} status={} outcome={} elapsedMs={}",
                    requestId(wrapped), TenantContext.getTenantId(), wrapped.getMethod(), wrapped.getRequestURI(),
                    safeParameters(wrapped), safeBody(wrapped), response.getStatus(), outcome,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }
    }

    private static String requestId(HttpServletRequest request) {
        String value = RequestContext.getRequestId();
        if (value == null || value.isBlank()) value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? "-" : value;
    }

    private static Map<String, Object> safeParameters(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            result.put(name, isSecret(name) ? "[REDACTED]" : Arrays.asList(values));
        });
        return result;
    }

    private static String safeBody(ContentCachingRequestWrapper request) {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json")) return "-";
        byte[] content = request.getContentAsByteArray();
        if (content.length == 0) return "-";
        String body = new String(content, StandardCharsets.UTF_8);
        body = JSON_SECRET.matcher(body).replaceAll("$1[REDACTED]");
        return body.length() <= MAX_BODY_LENGTH ? body : body.substring(0, MAX_BODY_LENGTH) + "...(truncated)";
    }

    private static boolean isSecret(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return value.contains("token") || value.contains("password") || value.contains("secret")
                || value.contains("skey") || value.contains("signature")
                || value.contains("authorization") || value.contains("credential");
    }
}
