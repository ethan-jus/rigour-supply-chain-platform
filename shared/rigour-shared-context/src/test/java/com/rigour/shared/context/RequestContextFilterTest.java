package com.rigour.shared.context;

import jakarta.servlet.ServletException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextFilterTest {

    private final TrustedContextSigner signer = signer();
    private final RequestContextFilter filter = new RequestContextFilter(signer);

    @AfterEach
    void clearContext() {
        RequestContext.clear();
        TenantContext.clear();
        AuthorizationContext.clear();
    }

    @Test
    void exposesSignedCallerDuringRequestAndClearsItAfterwards() throws Exception {
        MockHttpServletRequest request = signedTenantRequest();
        request.addHeader(RequestHeaders.REQUEST_ID, "request-123");
        request.addHeader(RequestHeaders.ACCEPT_LANGUAGE, "zh-CN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertThat(RequestContext.getRequestId()).isEqualTo("request-123");
            assertThat(RequestContext.getAcceptLanguage()).isEqualTo("zh-CN");
            assertThat(TenantContext.getTenantId()).isEqualTo("019fb000-0000-7000-8000-000000000002");
            CallerIdentity caller = AuthorizationContext.requireCurrent();
            assertThat(caller.principalId().toString()).isEqualTo("019fb000-0000-7000-8000-000000000001");
            assertThat(caller.sessionVersion()).isEqualTo(2);
            assertThat(caller.roles()).containsExactlyInAnyOrder("ADMIN", "OPERATOR");
            assertThat(AuthorizationContext.hasPermission("iam:user:write")).isTrue();
        });

        assertThat(response.getHeader(RequestHeaders.REQUEST_ID)).isEqualTo("request-123");
        assertThat(RequestContext.getRequestId()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(AuthorizationContext.current()).isEmpty();
    }

    @Test
    void rejectsUnsignedOrTamperedTrustedHeaders() throws Exception {
        MockHttpServletRequest unsigned = new MockHttpServletRequest("GET", "/api/v1/orders");
        unsigned.addHeader(RequestHeaders.TENANT_ID, "019fb000-0000-7000-8000-000000000002");
        MockHttpServletResponse unsignedResponse = new MockHttpServletResponse();
        filter.doFilter(unsigned, unsignedResponse,
                (request, response) -> { throw new AssertionError("unsigned request must not pass"); });
        assertThat(unsignedResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest tampered = signedTenantRequest();
        tampered.removeHeader(RequestHeaders.PERMISSIONS);
        tampered.addHeader(RequestHeaders.PERMISSIONS, "*:*:*");
        MockHttpServletResponse tamperedResponse = new MockHttpServletResponse();
        filter.doFilter(tampered, tamperedResponse,
                (request, response) -> { throw new AssertionError("tampered request must not pass"); });
        assertThat(tamperedResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void clearsContextsWhenDownstreamThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> { throw new ServletException("downstream failed"); }))
                .isInstanceOf(ServletException.class).hasMessage("downstream failed");

        assertThat(RequestContext.getRequestId()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(AuthorizationContext.current()).isEmpty();
    }

    @Test
    void generatesRequestIdAndUsesDefaultLanguageForAnonymousRequest() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (ignoredRequest, ignoredResponse) -> {
            assertThat(RequestContext.getRequestId()).isNotBlank();
            assertThat(RequestContext.getAcceptLanguage()).isEqualTo(RequestContextFilter.DEFAULT_LANGUAGE);
            assertThat(AuthorizationContext.current()).isEmpty();
        });
        assertThat(response.getHeader(RequestHeaders.REQUEST_ID)).isNotBlank();
    }

    private MockHttpServletRequest signedTenantRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        Map<String, String> values = new LinkedHashMap<>();
        values.put(RequestHeaders.PRINCIPAL_SCOPE, "TENANT");
        values.put(RequestHeaders.PRINCIPAL_ID, "019fb000-0000-7000-8000-000000000001");
        values.put(RequestHeaders.USER_ID, "019fb000-0000-7000-8000-000000000001");
        values.put(RequestHeaders.TENANT_ID, "019fb000-0000-7000-8000-000000000002");
        values.put(RequestHeaders.SESSION_ID, "019fb000-0000-7000-8000-000000000003");
        values.put(RequestHeaders.SESSION_VERSION, "2");
        values.put(RequestHeaders.USER_SECURITY_VERSION, "3");
        values.put(RequestHeaders.TENANT_POLICY_VERSION, "4");
        values.put(RequestHeaders.ROLES, "ADMIN,OPERATOR");
        values.put(RequestHeaders.PERMISSIONS, "iam:user:read,iam:user:write");
        TrustedContextSigner.SignedContext signature = signer.sign(request, values);
        values.forEach(request::addHeader);
        request.addHeader(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
        request.addHeader(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
        request.addHeader(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
        return request;
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
