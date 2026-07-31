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
            PrincipalScope scope = PrincipalScope.valueOf(required(request, "principalScope").toUpperCase());
            String password = required(request, "password");
            return getAuthenticationManager().authenticate(new IamLoginAuthenticationToken(
                    scope,
                    request.getParameter("tenantCode"),
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
