package com.rigour.tenant.iam.api;

import com.rigour.shared.context.AuthenticationFailureCodes;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.tenant.iam.application.service.settings.AppGrantDeniedException;
import java.util.List;
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
        assertThat(response.getBody().details()).isEmpty();
    }

    @Test
    void distinguishesMissingGrantFunctionsFromDataScopeWithoutLeakingInternalReasons() {
        RequestContext.set("request-grant-functions", "zh-CN");
        var response = handler.handleAccessDenied(AppGrantDeniedException.functions(
                List.of("hr:position:write", "hr:position:read", "hr:position:read")));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        var body = response.getBody();
        assertThat(body.code()).isEqualTo("IAM_GRANT_FUNCTION_DENIED");
        assertThat(body.message()).isEqualTo("当前账号不能授予未拥有的功能：hr:position:read、hr:position:write");
        assertThat(body.requestId()).isEqualTo("request-grant-functions");
        assertThat(body.details()).hasSize(2).allSatisfy(detail -> {
            assertThat(detail.field()).isEqualTo("menuNodeIds");
            assertThat(detail.reason()).isEqualTo("PERMISSION_NOT_OWNED");
        });
        assertThat(body.details()).extracting(detail -> detail.message())
                .containsExactly("hr:position:read", "hr:position:write");

        var scopeResponse = handler.handleAccessDenied(AppGrantDeniedException.dataScope());
        assertThat(scopeResponse.getStatusCode().value()).isEqualTo(403);
        assertThat(scopeResponse.getBody().code()).isEqualTo("IAM_GRANT_SCOPE_DENIED");
        assertThat(scopeResponse.getBody().message()).isEqualTo("待授予的数据范围超出当前账号的授权范围");
        assertThat(scopeResponse.getBody().details()).singleElement().satisfies(detail -> {
            assertThat(detail.field()).isEqualTo("dataScope");
            assertThat(detail.reason()).isEqualTo("SCOPE_NOT_COVERED");
        });
    }
}
