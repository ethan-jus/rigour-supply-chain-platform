package com.rigour.shared.core.web;

import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

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
}
