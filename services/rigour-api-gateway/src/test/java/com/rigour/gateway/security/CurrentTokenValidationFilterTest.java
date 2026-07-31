package com.rigour.gateway.security;

import com.rigour.gateway.config.GatewaySecurityProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpStatus;

class CurrentTokenValidationFilterTest {

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void obtainsCurrentRolesAndPermissionsFromIamBeforeForwarding() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://iam.test/api/v1/token/current"))
                .andExpect(method(HttpMethod.GET)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer signed-token"))
                .andRespond(withSuccess("{\"roles\":[\"ADMIN\"],\"permissions\":[\"iam:user:read\"]}",
                        MediaType.APPLICATION_JSON));
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setCurrentTokenValidationEnabled(true);
        properties.setIamCurrentTokenUri("https://iam.test/api/v1/token/current");
        CurrentTokenValidationFilter filter = new CurrentTokenValidationFilter(builder.build(), properties);
        Jwt jwt = new Jwt("signed-token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), Map.of("sub", "user", "aud", List.of("rigour-api")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
        SecurityContextHolder.setContext(context);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        AtomicReference<Object> permissions = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (forwarded, response) -> permissions.set(
                        ((MockHttpServletRequest) forwarded).getAttribute(CurrentTokenValidationFilter.permissionsAttribute())));
        assertThat(permissions.get()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.SET)
                .contains("iam:user:read");
        server.verify();
    }

    @Test
    void mapsIamTokenRejectionToUnauthorized() throws Exception {
        FilterFixture fixture = fixture(withStatus(HttpStatus.UNAUTHORIZED));
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.filter().doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response,
                (request, forwardedResponse) -> { throw new AssertionError("request must not be forwarded"); });
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        fixture.server().verify();
    }

    @Test
    void mapsIamFailureToServiceUnavailable() throws Exception {
        FilterFixture fixture = fixture(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.filter().doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response,
                (request, forwardedResponse) -> { throw new AssertionError("request must not be forwarded"); });
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        fixture.server().verify();
    }

    private FilterFixture fixture(org.springframework.test.web.client.ResponseCreator responseCreator) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://iam.test/api/v1/token/current")).andRespond(responseCreator);
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setCurrentTokenValidationEnabled(true);
        properties.setIamCurrentTokenUri("https://iam.test/api/v1/token/current");
        Jwt jwt = new Jwt("signed-token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), Map.of("sub", "user", "aud", List.of("rigour-api")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
        SecurityContextHolder.setContext(context);
        return new FilterFixture(new CurrentTokenValidationFilter(builder.build(), properties), server);
    }

    private record FilterFixture(CurrentTokenValidationFilter filter, MockRestServiceServer server) { }
}
