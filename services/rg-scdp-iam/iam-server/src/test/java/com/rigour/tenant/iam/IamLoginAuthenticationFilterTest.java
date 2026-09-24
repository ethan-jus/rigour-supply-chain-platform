package com.rigour.tenant.iam;

import com.rigour.tenant.iam.infrastructure.security.session.IamLoginAuthenticationFilter;
import com.rigour.tenant.iam.infrastructure.security.session.IamLoginAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IamLoginAuthenticationFilterTest {
    @Test void omittedEnterpriseUsesConfiguredDefault() {
        var filter = new IamLoginAuthenticationFilter(authentication -> authentication, "rigour_media");
        var result = (IamLoginAuthenticationToken) filter.attemptAuthentication(request(),new MockHttpServletResponse());
        assertThat(result.tenantCode()).isEqualTo("rigour_media");
    }
    @Test void explicitEnterpriseIsNeverSilentlyReplacedByDefault() {
        var request = request(); request.setParameter("tenantCode","another-enterprise");
        var filter = new IamLoginAuthenticationFilter(authentication -> authentication, "rigour_media");
        var result = (IamLoginAuthenticationToken) filter.attemptAuthentication(request,new MockHttpServletResponse());
        assertThat(result.tenantCode()).isEqualTo("another-enterprise");
    }
    @Test void missingDefaultAndPlatformScopeFailClosed() {
        var manager = mock(AuthenticationManager.class);
        assertThatThrownBy(() -> new IamLoginAuthenticationFilter(manager,null).attemptAuthentication(request(),new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);
        var request=request(); request.setParameter("principalScope","PLATFORM");
        assertThatThrownBy(() -> new IamLoginAuthenticationFilter(manager,"rigour_media").attemptAuthentication(request,new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(manager);
    }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST","/scdp/login");
        request.setParameter("username","admin"); request.setParameter("password","test-only-password");
        return request;
    }
}
