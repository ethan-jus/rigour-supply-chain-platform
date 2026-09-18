package com.rigour.shared.core.web;

import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void formatsBusinessExceptionWithProtocolStatusAndRequestId() {
        RequestContext.set("request-error", "zh-CN");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.CONFLICT, "状态已变化", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("状态已变化");
        assertThat(response.getBody().requestId()).isEqualTo("request-error");
    }

    @Test
    void mapsAuthorizationDenialToStableForbiddenResponse() {
        RequestContext.set("request-forbidden", "zh-CN");
        ResponseEntity<ApiResponse<Void>> response = handler.handleAuthorizationDenied(
                new AuthorizationDeniedException("order:read"));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IAM_FORBIDDEN");
        assertThat(response.getBody().requestId()).isEqualTo("request-forbidden");
    }

    @Test
    void mapsDownstreamConnectionFailureToServiceUnavailable() {
        RequestContext.set("request-downstream", "zh-CN");
        ResponseEntity<ApiResponse<Void>> response = handler.handleResourceAccess(
                new ResourceAccessException("Connection refused"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.getBody().requestId()).isEqualTo("request-downstream");
    }

    @Test
    void mapsMissingStaticResourceToNotFoundResponse() {
        RequestContext.set("request-not-found", "zh-CN");
        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "", "favicon.ico"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().requestId()).isEqualTo("request-not-found");
    }

    @Test
    void ignoresBrokenPipeAsClientDisconnectInsteadOfServerError() {
        RequestContext.set("request-client-abort", "zh-CN");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(
                new HttpMessageNotWritableException("response write failed", new IOException("Broken pipe")));

        assertThat(response).isNull();
    }

    @Test
    void stillMapsUnexpectedExceptionToInternalError() {
        RequestContext.set("request-unexpected", "zh-CN");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException("unexpected failure"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
