package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** 对后台数据、媒体和写操作执行数据库会话认证及 CSRF 校验。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
final class TemporaryCheckinAdminAuthenticationFilter extends OncePerRequestFilter {

    static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String ADMIN_PREFIX = "/sales-checkin/admin/";
    private static final String LOGIN_PATH = ADMIN_PREFIX + "api/v1/auth/login";
    private static final String ME_PATH = ADMIN_PREFIX + "api/v1/auth/me";
    private static final String LOGOUT_PATH = ADMIN_PREFIX + "api/v1/auth/logout";
    private static final String CHANGE_PASSWORD_PATH = ADMIN_PREFIX + "api/v1/auth/change-password";

    private final TemporaryCheckinAdminAuthService authService;
    private final TemporaryCheckinAdminAuthProperties properties;
    private final ObjectMapper objectMapper;

    TemporaryCheckinAdminAuthenticationFilter(
            TemporaryCheckinAdminAuthService authService,
            TemporaryCheckinAdminAuthProperties properties,
            ObjectMapper objectMapper) {
        this.authService = authService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        if (properties.getCookieName() == null || !properties.getCookieName().startsWith("__Host-")) {
            throw new IllegalStateException("后台会话Cookie必须使用__Host-前缀");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith(ADMIN_PREFIX)) return true;
        if (LOGIN_PATH.equals(path) && "POST".equals(request.getMethod())) return true;
        return isPublicPageAsset(request, path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawToken = cookie(request, properties.getCookieName());
        TemporaryCheckinAdminPrincipal principal = authService.authenticate(rawToken);
        if (principal == null) {
            clearSessionCookie(response);
            writeError(response, 401, "TEMP_CHECKIN_ADMIN_UNAUTHORIZED", "请先登录后台");
            return;
        }
        request.setAttribute(TemporaryCheckinAdminPrincipal.REQUEST_ATTRIBUTE, principal);
        String path = request.getRequestURI();
        if (principal.mustChangePassword() && !ME_PATH.equals(path)
                && !LOGOUT_PATH.equals(path) && !CHANGE_PASSWORD_PATH.equals(path)) {
            writeError(response, 403, "TEMP_CHECKIN_PASSWORD_CHANGE_REQUIRED", "请先修改临时密码");
            return;
        }
        if (requiresCsrf(request) && !constantTimeEquals(principal.csrfToken(), request.getHeader(CSRF_HEADER))) {
            writeError(response, 403, "TEMP_CHECKIN_ADMIN_CSRF_INVALID", "请求校验已失效，请刷新后重试");
            return;
        }
        chain.doFilter(request, response);
    }

    void clearSessionCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), "")
                .httpOnly(true).secure(true).sameSite("Strict").path("/")
                .maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static boolean isPublicPageAsset(HttpServletRequest request, String path) {
        if (!ListOfMethods.isRead(request.getMethod())) return false;
        return ADMIN_PREFIX.equals(path)
                || (ADMIN_PREFIX + "index.html").equals(path)
                || (ADMIN_PREFIX + "admin.css").equals(path)
                || (ADMIN_PREFIX + "admin.js").equals(path);
    }

    private static boolean requiresCsrf(HttpServletRequest request) {
        return !ListOfMethods.isRead(request.getMethod());
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies).filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message));
    }

    /** 避免引入安全框架时仍集中定义不需要CSRF的只读HTTP方法。 */
    private static final class ListOfMethods {
        private ListOfMethods() { }
        static boolean isRead(String method) {
            return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        }
    }
}
