package com.rigour.shared.context;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextFilterTest {

    private final RequestContextFilter filter = new RequestContextFilter();

    @AfterEach
    void clearContext() {
        RequestContext.clear();
        TenantContext.clear();
    }

    @Test
    void exposesHeadersDuringRequestAndClearsThemAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestHeaders.REQUEST_ID, "request-123");
        request.addHeader(RequestHeaders.TENANT_ID, "tenant-a");
        request.addHeader(RequestHeaders.ACCEPT_LANGUAGE, "zh-CN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertThat(RequestContext.getRequestId()).isEqualTo("request-123");
            assertThat(RequestContext.getAcceptLanguage()).isEqualTo("zh-CN");
            assertThat(TenantContext.getTenantId()).isEqualTo("tenant-a");
        });

        assertThat(response.getHeader(RequestHeaders.REQUEST_ID)).isEqualTo("request-123");
        assertThat(RequestContext.getRequestId()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void clearsContextsWhenDownstreamThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestHeaders.TENANT_ID, "tenant-a");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> {
                    throw new ServletException("downstream failed");
                }))
                .isInstanceOf(ServletException.class)
                .hasMessage("downstream failed");

        assertThat(RequestContext.getRequestId()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void generatesRequestIdAndUsesDefaultLanguage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (ignoredRequest, ignoredResponse) -> {
            assertThat(RequestContext.getRequestId()).isNotBlank();
            assertThat(RequestContext.getAcceptLanguage()).isEqualTo(RequestContextFilter.DEFAULT_LANGUAGE);
        });

        assertThat(response.getHeader(RequestHeaders.REQUEST_ID)).isNotBlank();
    }
}
