package com.rigour.tenant.iam.api;

import com.rigour.shared.context.AuthenticationFailureCodes;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class IamAccessDeniedExceptionHandlerTest {

    private final IamAccessDeniedExceptionHandler handler = new IamAccessDeniedExceptionHandler();

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void mapsSpringAccessDeniedExceptionToStableForbiddenResponse() {
        RequestContext.set("request-iam-forbidden", "zh-CN");

        ResponseEntity<ApiResponse<Void>> response = handler.handleAccessDenied(
                new AccessDeniedException("Platform super administrator is required"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(AuthenticationFailureCodes.IAM_FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo("没有访问该资源的权限");
        assertThat(response.getBody().requestId()).isEqualTo("request-iam-forbidden");
    }
}
