package com.rigour.gateway.security;

import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** 删除客户端身份头并仅从已验签JWT重建下游可信上下文。 */
public final class TrustedContextFilter extends OncePerRequestFilter {

    public static final String PREFIX = "X-Rigour-";
    private static final java.util.regex.Pattern SAFE_AUTHORITY =
            java.util.regex.Pattern.compile("[A-Za-z0-9*][A-Za-z0-9*._:-]{0,127}");
    private final TrustedContextSigner contextSigner;

    public TrustedContextFilter(TrustedContextSigner contextSigner) {
        this.contextSigner = contextSigner;
        this.contextSigner.requireSigningConfiguration();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Map<String, String> trusted = new LinkedHashMap<>();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && authentication.isAuthenticated()) {
            var jwt = jwtAuthentication.getToken();
            put(trusted, RequestHeaders.PRINCIPAL_SCOPE, jwt.getClaimAsString("principalScope"));
            put(trusted, RequestHeaders.PRINCIPAL_ID, jwt.getClaimAsString("principalId"));
            put(trusted, RequestHeaders.TENANT_ID, jwt.getClaimAsString("tenantId"));
            put(trusted, RequestHeaders.USER_ID, jwt.getClaimAsString("userId"));
            put(trusted, RequestHeaders.PLATFORM_USER_ID, jwt.getClaimAsString("platformUserId"));
            put(trusted, RequestHeaders.SESSION_ID, jwt.getClaimAsString("sessionId"));
            put(trusted, RequestHeaders.SESSION_VERSION, stringClaim(jwt.getClaims().get("sessionVersion")));
            put(trusted, RequestHeaders.USER_SECURITY_VERSION, stringClaim(jwt.getClaims().get("userSecurityVersion")));
            put(trusted, RequestHeaders.TENANT_POLICY_VERSION, stringClaim(jwt.getClaims().get("tenantPolicyVersion")));
            put(trusted, RequestHeaders.ROLES, joined(request.getAttribute(CurrentTokenValidationFilter.rolesAttribute())));
            put(trusted, RequestHeaders.PERMISSIONS, joined(request.getAttribute(CurrentTokenValidationFilter.permissionsAttribute())));
            try {
                TrustedContextSigner.SignedContext signature = contextSigner.sign(request, trusted);
                trusted.put(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
                trusted.put(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
                trusted.put(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
            } catch (IllegalArgumentException | IllegalStateException exception) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Trusted authorization context is unavailable");
                return;
            }
        }
        filterChain.doFilter(new TrustedHeaderRequest(request, trusted), response);
    }

    private static void put(Map<String, String> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }

    private static String stringClaim(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String joined(Object value) {
        if (!(value instanceof Set<?> values)) return null;
        return values.stream().map(String::valueOf).peek(item -> {
            if (!SAFE_AUTHORITY.matcher(item).matches()) {
                throw new IllegalArgumentException("Unsafe authority value");
            }
        }).sorted().collect(java.util.stream.Collectors.joining(","));
    }

    private static final class TrustedHeaderRequest extends HttpServletRequestWrapper {
        private final Map<String, String> trusted;

        private TrustedHeaderRequest(HttpServletRequest request, Map<String, String> trusted) {
            super(request);
            this.trusted = Map.copyOf(trusted);
        }

        @Override
        public String getHeader(String name) {
            String canonical = canonical(name);
            return canonical == null ? super.getHeader(name) : trusted.get(canonical);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String value = getHeader(name);
            return value == null ? Collections.emptyEnumeration()
                    : Collections.enumeration(List.of(value));
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new java.util.LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                String name = original.nextElement();
                if (canonical(name) == null) {
                    names.add(name);
                }
            }
            names.addAll(trusted.keySet());
            return Collections.enumeration(names);
        }

        private static String canonical(String name) {
            if (name == null || !name.toLowerCase(Locale.ROOT).startsWith(PREFIX.toLowerCase(Locale.ROOT))) {
                return null;
            }
            return RequestHeaders.ALL_CONTEXT_HEADERS.stream().filter(header -> header.equalsIgnoreCase(name)).findFirst()
                    .orElse(name);
        }
    }
}
