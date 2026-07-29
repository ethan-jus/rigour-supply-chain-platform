package com.rigour.shared.core.api;

import com.rigour.shared.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void successUsesRequestIdFromContext() {
        RequestContext.set("request-123", "zh-CN");

        ApiResponse<String> response = ApiResponse.success("payload");

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.requestId()).isEqualTo("request-123");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void errorUsesSameRequestIdContract() {
        RequestContext.set("request-456", "zh-CN");

        ApiResponse<Void> response = ApiResponse.error(ErrorCode.NOT_FOUND);

        assertThat(response.code()).isEqualTo("NOT_FOUND");
        assertThat(response.data()).isNull();
        assertThat(response.details()).isEmpty();
        assertThat(response.requestId()).isEqualTo("request-456");
    }
}
