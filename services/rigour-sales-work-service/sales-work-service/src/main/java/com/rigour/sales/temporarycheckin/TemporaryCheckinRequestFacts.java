package com.rigour.sales.temporarycheckin;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;

/**
 * Nginx 覆盖后的请求来源与浏览器身份 Cookie。
 * 不解析浏览器传入的 X-Forwarded-For，避免将可伪造链路当成风险事实。
 */
record TemporaryCheckinRequestFacts(
        String clientIp,
        String proxyMarker,
        String userAgent,
        String deviceCookie,
        String identityCookie) {

    static final String CLIENT_IP_HEADER = "X-Sales-Checkin-Client-IP";
    static final String PROXY_MARKER_HEADER = "X-Sales-Checkin-Proxy-Marker";

    static TemporaryCheckinRequestFacts from(HttpServletRequest request) {
        return new TemporaryCheckinRequestFacts(
                request.getHeader(CLIENT_IP_HEADER),
                request.getHeader(PROXY_MARKER_HEADER),
                request.getHeader("User-Agent"),
                cookie(request, TemporaryCheckinSalesIdentityService.DEVICE_COOKIE),
                cookie(request, TemporaryCheckinSalesIdentityService.IDENTITY_COOKIE));
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
