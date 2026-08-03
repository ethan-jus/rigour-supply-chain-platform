package com.rigour.tenant.iam.infrastructure.security.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 处理OIDC {@code prompt=login}，确保退出后的下一次授权不会静默复用旧浏览器会话。
 *
 * <p>Spring Authorization Server负责校验prompt参数，但不会替应用主动注销已认证的
 * HttpSession。这里先撤销当前IAM会话、清理会话Cookie，再保存原始授权请求并转到登录页。
 * 登录成功后用一次性会话标记放行该授权请求，避免因原请求仍带prompt=login而重复重定向。</p>
 */
public final class OidcPromptLoginFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OidcPromptLoginFilter.class);
    private static final String FORCE_LOGIN_COMPLETED_ATTRIBUTE =
            OidcPromptLoginFilter.class.getName() + ".FORCE_LOGIN_COMPLETED";

    private final RequestCache requestCache;
    private final IamSessionLogoutHandler iamSessionLogoutHandler;

    public OidcPromptLoginFilter(RequestCache requestCache, IamSessionLogoutHandler iamSessionLogoutHandler) {
        this.requestCache = requestCache;
        this.iamSessionLogoutHandler = iamSessionLogoutHandler;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!requiresFreshLogin(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isAuthenticated(authentication)) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(FORCE_LOGIN_COMPLETED_ATTRIBUTE))) {
            session.removeAttribute(FORCE_LOGIN_COMPLETED_ATTRIBUTE);
            filterChain.doFilter(request, response);
            return;
        }

        iamSessionLogoutHandler.logout(request, response, authentication);
        new CompositeLogoutHandler(
                new SecurityContextLogoutHandler(),
                new CookieClearingLogoutHandler("RIGOUR_IAM_SESSION"))
                .logout(request, response, authentication);

        // SecurityContextLogoutHandler会使旧HttpSession失效，因此必须在其之后保存授权请求。
        requestCache.saveRequest(request, response);
        request.getSession(true).setAttribute(FORCE_LOGIN_COMPLETED_ATTRIBUTE, Boolean.TRUE);
        log.info("OIDC请求要求重新登录，已清理当前IAM浏览器会话");
        response.sendRedirect(request.getContextPath() + "/login");
    }

    private static boolean requiresFreshLogin(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())
                || !"/oauth2/authorize".equals(request.getRequestURI())) {
            return false;
        }
        String prompt = request.getParameter("prompt");
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        for (String value : prompt.split("\\s+")) {
            if ("login".equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

}
