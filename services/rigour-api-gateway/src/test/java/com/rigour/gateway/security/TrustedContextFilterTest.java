package com.rigour.gateway.security;

import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.TrustedContextSigner;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedContextFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replacesEveryClientIdentityHeaderWithSignedClaims() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader("X-Rigour-Tenant-Id", "forged-tenant");
        request.addHeader("X-Rigour-Admin", "forged-extra-header");
        request.setAttribute(CurrentTokenValidationFilter.rolesAttribute(), Set.of("TENANT_ADMIN"));
        request.setAttribute(CurrentTokenValidationFilter.permissionsAttribute(),
                Set.of("iam:user:read", "iam:user:write"));
        Jwt jwt = new Jwt("signed-token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), Map.of(
                "sub", "019fb000-0000-7000-8000-000000000001",
                "principalScope", "TENANT",
                "principalId", "019fb000-0000-7000-8000-000000000001",
                "userId", "019fb000-0000-7000-8000-000000000001",
                "tenantId", "019fb000-0000-7000-8000-000000000002",
                "sessionId", "019fb000-0000-7000-8000-000000000003",
                "sessionVersion", 2,
                "userSecurityVersion", 3,
                "tenantPolicyVersion", 4,
                "aud", List.of("rigour-api")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(
                jwt, AuthorityUtils.createAuthorityList("SCOPE_openid")));
        SecurityContextHolder.setContext(context);
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();

        AtomicReference<HttpServletRequest> forwarded = new AtomicReference<>();
        TrustedContextSigner signer = signer();
        new TrustedContextFilter(signer).doFilter(request, new MockHttpServletResponse(),
                (filteredRequest, response) -> forwarded.set((HttpServletRequest) filteredRequest));

        assertThat(forwarded.get().getHeader("X-Rigour-Tenant-Id"))
                .isEqualTo("019fb000-0000-7000-8000-000000000002");
        assertThat(forwarded.get().getHeader("X-Rigour-Session-Version")).isEqualTo("2");
        assertThat(forwarded.get().getHeader("X-Rigour-Admin")).isNull();
        assertThat(forwarded.get().getHeader("X-Rigour-Roles")).isEqualTo("TENANT_ADMIN");
        assertThat(forwarded.get().getHeader("X-Rigour-Permissions"))
                .isEqualTo("iam:user:read,iam:user:write");
        assertThat(signer.verify(forwarded.get())).isTrue();
    }

    @Test
    void removesForgedIdentityHeadersWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader("X-Rigour-Principal-Id", "forged");
        AtomicReference<HttpServletRequest> forwarded = new AtomicReference<>();

        new TrustedContextFilter(signer()).doFilter(request, new MockHttpServletResponse(),
                (filteredRequest, response) -> forwarded.set((HttpServletRequest) filteredRequest));

        assertThat(forwarded.get().getHeader("X-Rigour-Principal-Id")).isNull();
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
