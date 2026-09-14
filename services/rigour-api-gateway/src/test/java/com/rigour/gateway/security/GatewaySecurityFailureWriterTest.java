package com.rigour.gateway.security;

import com.rigour.shared.context.AuthenticationFailureCodes;
import com.rigour.shared.context.RequestHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityFailureWriterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final GatewaySecurityFailureWriter writer = new GatewaySecurityFailureWriter(jsonMapper);

    @Test
    void defaultAuthenticationEntryPointReturnsStableTokenInvalidResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(RequestHeaders.REQUEST_ID, "request-123");

        writer.tokenInvalidEntryPoint().commence(
                new MockHttpServletRequest(), response, new BadCredentialsException("invalid"));

        assertFailure(response, HttpStatus.UNAUTHORIZED, AuthenticationFailureCodes.IAM_TOKEN_INVALID);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(response.getHeader(RequestHeaders.REQUEST_ID)).isEqualTo("request-123");
    }

    @Test
    void defaultAccessDeniedHandlerReturnsStableForbiddenResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.forbiddenHandler().handle(
                new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertFailure(response, HttpStatus.FORBIDDEN, AuthenticationFailureCodes.IAM_FORBIDDEN);
    }

    @Test
    void disabledSecurityChainEntryPointReturnsStableServiceUnavailableResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.sessionCheckUnavailableEntryPoint().commence(
                new MockHttpServletRequest(), response, new BadCredentialsException("disabled"));

        assertFailure(response, HttpStatus.SERVICE_UNAVAILABLE,
                AuthenticationFailureCodes.IAM_SESSION_CHECK_UNAVAILABLE);
    }

    @Test
    void disabledSecurityChainAccessDeniedReturnsStableServiceUnavailableResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.sessionCheckUnavailableHandler().handle(
                new MockHttpServletRequest(), response, new AccessDeniedException("disabled"));

        assertFailure(response, HttpStatus.SERVICE_UNAVAILABLE,
                AuthenticationFailureCodes.IAM_SESSION_CHECK_UNAVAILABLE);
    }

    private void assertFailure(MockHttpServletResponse response, HttpStatus status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status.value());
        assertThat(response.getHeader(RequestHeaders.AUTH_FAILURE)).isEqualTo(code);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(jsonMapper.readTree(response.getContentAsByteArray()).get("code").asString()).isEqualTo(code);
    }
}
