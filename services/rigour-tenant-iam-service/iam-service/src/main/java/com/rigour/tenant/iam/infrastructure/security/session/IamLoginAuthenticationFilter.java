package com.rigour.tenant.iam.infrastructure.security.session;

import com.rigour.tenant.iam.domain.model.session.AuthSession.ClientType;
import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** 将统一登录表单转换为携带租户范围与安全元数据的IAM认证请求。 */
public final class IamLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    public IamLoginAuthenticationFilter(AuthenticationManager authenticationManager) {
        super(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/login"), authenticationManager);
    }

    @Override
    public Authentication attemptAuthentication(
            HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
        try {
            String tenantCode = normalized(request.getParameter("tenantCode"));
            // 新登录页不暴露平台/租户身份选择器：填写企业编码即租户登录，留空即平台登录。
            // 兼容旧客户端显式提交的scope，但必须与企业编码一致，不能借此跨范围登录。
            String requestedScope = normalized(request.getParameter("principalScope"));
            PrincipalScope scope = requestedScope == null
                    ? (tenantCode == null ? PrincipalScope.PLATFORM : PrincipalScope.TENANT)
                    : PrincipalScope.valueOf(requestedScope.toUpperCase());
            if ((scope == PrincipalScope.TENANT) != (tenantCode != null)) {
                throw new IllegalArgumentException("login scope and tenant code do not match");
            }
            String password = required(request, "password");
            return getAuthenticationManager().authenticate(new IamLoginAuthenticationToken(
                    scope,
                    tenantCode,
                    required(request, "username"),
                    password.toCharArray(),
                    ClientType.WEB,
                    normalized(request.getParameter("deviceName")),
                    null,
                    sha256(request.getHeader("User-Agent")),
                    remoteAddress(request)
            ));
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("Authentication failed");
        }
    }

    private static String required(HttpServletRequest request, String name) {
        String value = normalized(request.getParameter(name));
        if (value == null) {
            throw new IllegalArgumentException("missing login parameter");
        }
        return value;
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static byte[] sha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static byte[] remoteAddress(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).getAddress();
        } catch (UnknownHostException exception) {
            throw new BadCredentialsException("Authentication failed");
        }
    }
}
