package com.rigour.tenant.iam.infrastructure.security.session;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * 登录成功后的 OIDC 浏览器跳转处理器。
 *
 * <p>只有授权码请求可以作为登录后的恢复目标。浏览器曾访问过的错误页、登录页或
 * 其他受保护路径不能被恢复，否则会把用户再次带到 IAM 的拒绝页。</p>
 */
public final class OidcLoginAuthenticationSuccessHandler
        extends SavedRequestAwareAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginAuthenticationSuccessHandler.class);
    private final RequestCache requestCache;

    public OidcLoginAuthenticationSuccessHandler(RequestCache requestCache) {
        this.requestCache = requestCache;
        setRequestCache(requestCache);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws ServletException, IOException {
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null && !isAuthorizationRequest(savedRequest.getRedirectUrl())) {
            log.warn("IAM登录成功后清理不可恢复的请求 path={}", safePath(savedRequest.getRedirectUrl()));
            requestCache.removeRequest(request, response);
        } else if (savedRequest != null) {
            log.info("IAM登录成功，恢复OIDC授权请求 path=/oauth2/authorize");
        } else {
            log.info("IAM登录成功，没有待恢复的OIDC请求，回到Portal");
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    private static boolean isAuthorizationRequest(String redirectUrl) {
        if (redirectUrl == null || redirectUrl.isBlank()) {
            return false;
        }
        try {
            return "/oauth2/authorize".equals(URI.create(redirectUrl).getPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String safePath(String redirectUrl) {
        if (redirectUrl == null || redirectUrl.isBlank()) {
            return "-";
        }
        try {
            String path = URI.create(redirectUrl).getPath();
            return path == null || path.isBlank() ? "-" : path;
        } catch (IllegalArgumentException exception) {
            return "invalid";
        }
    }
}
