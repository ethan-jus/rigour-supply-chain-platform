package com.rigour.tenant.iam;

import com.rigour.tenant.iam.infrastructure.security.session.OidcLoginAuthenticationSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OidcLoginAuthenticationSuccessHandlerTest {

    @Test
    void clearsStaleErrorRequestAndFallsBackToPortal() throws Exception {
        RequestCache requestCache = mock(RequestCache.class);
        SavedRequest savedRequest = mock(SavedRequest.class);
        when(requestCache.getRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(savedRequest, null);
        when(savedRequest.getRedirectUrl()).thenReturn("http://localhost:26881/error?continue");

        OidcLoginAuthenticationSuccessHandler handler = new OidcLoginAuthenticationSuccessHandler(requestCache);
        handler.setDefaultTargetUrl("http://localhost:5100/");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = mock(Authentication.class);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(requestCache).removeRequest(request, response);
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5100/");
    }

    @Test
    void preservesOidcAuthorizationRequest() throws Exception {
        RequestCache requestCache = mock(RequestCache.class);
        SavedRequest savedRequest = mock(SavedRequest.class);
        when(requestCache.getRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(savedRequest);
        when(savedRequest.getRedirectUrl()).thenReturn(
                "http://localhost:26881/oauth2/authorize?response_type=code&client_id=rigour-portal-browser");

        OidcLoginAuthenticationSuccessHandler handler = new OidcLoginAuthenticationSuccessHandler(requestCache);
        handler.setDefaultTargetUrl("http://localhost:5100/");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = mock(Authentication.class);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo(savedRequest.getRedirectUrl());
    }
}
