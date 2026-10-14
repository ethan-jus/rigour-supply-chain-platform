package com.rigour.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.rigour.gateway.config.GatewaySecurityConfiguration;
import com.rigour.platform.http.ServiceAddressResolver;
import com.rigour.shared.context.AuthenticationFailureCodes;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import jakarta.servlet.Filter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Runs the production security chain and Nimbus decoder; only outbound JWKS transport is mocked. */
class GatewayJwtFailureClassificationTest {
    private static final String ISSUER = "https://iam.test";
    private static KeyPair trusted;
    private static KeyPair untrusted;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trusted = generator.generateKeyPair();
        untrusted = generator.generateKeyPair();
    }

    @Test
    void refusedJwksConnectionIsUnavailableAndNeverForwardsTheRequest() throws Exception {
        try (var context = context()) {
            when(context.getBean(ServiceAddressResolver.class).intercept(any(), any(), any()))
                    .thenThrow(new ConnectException("unreachable IAM instance"));
            String token = token(trusted, "test-key", Instant.now().plusSeconds(300));
            assertThatThrownBy(() -> authenticate(context, token))
                    .isInstanceOf(AuthenticationServiceException.class)
                    .hasRootCauseInstanceOf(ConnectException.class);
            assertFailure(context, token, HttpStatus.SERVICE_UNAVAILABLE,
                    AuthenticationFailureCodes.IAM_SESSION_CHECK_UNAVAILABLE);
        }
    }

    @Test
    void jwksTimeoutIsUnavailableAndNeverForwardsTheRequest() throws Exception {
        try (var context = context()) {
            when(context.getBean(ServiceAddressResolver.class).intercept(any(), any(), any()))
                    .thenThrow(new SocketTimeoutException("JWKS read timed out"));
            String token = token(trusted, "test-key", Instant.now().plusSeconds(300));
            assertThatThrownBy(() -> authenticate(context, token))
                    .isInstanceOf(AuthenticationServiceException.class)
                    .hasRootCauseInstanceOf(SocketTimeoutException.class);
            assertFailure(context, token, HttpStatus.SERVICE_UNAVAILABLE,
                    AuthenticationFailureCodes.IAM_SESSION_CHECK_UNAVAILABLE);
        }
    }

    @Test
    void jwksHttpFailureIsUnavailableInsteadOfExpiringTheUsersSession() throws Exception {
        try (var context = context()) {
            jwksResponse(context, HttpStatus.SERVICE_UNAVAILABLE, "unavailable upstream");
            assertFailure(context, token(trusted, "test-key", Instant.now().plusSeconds(300)),
                    HttpStatus.SERVICE_UNAVAILABLE, AuthenticationFailureCodes.IAM_SESSION_CHECK_UNAVAILABLE);
        }
    }

    @Test
    void malformedJwksIsAnAuthenticationServiceFailure() throws Exception {
        try (var context = context()) {
            jwksResponse(context, HttpStatus.OK, "not a JSON key set");
            assertFailure(context, token(trusted, "test-key", Instant.now().plusSeconds(300)),
                    HttpStatus.SERVICE_UNAVAILABLE, AuthenticationFailureCodes.IAM_SESSION_CHECK_UNAVAILABLE);
        }
    }

    @Test
    void malformedTokenRemainsUnauthorizedWithoutRequestingJwks() throws Exception {
        try (var context = context()) {
            assertFailure(context, "not-a-jwt", HttpStatus.UNAUTHORIZED,
                    AuthenticationFailureCodes.IAM_TOKEN_INVALID);
            verifyNoInteractions(context.getBean(ServiceAddressResolver.class));
        }
    }

    @Test
    void badSignatureExpiredTokenAndUnknownKeyRemainUnauthorized() throws Exception {
        try (var context = context()) {
            jwksResponse(context, HttpStatus.OK, new JWKSet(publicKey(trusted)).toString());
            for (String token : List.of(
                    token(untrusted, "test-key", Instant.now().plusSeconds(300)),
                    token(trusted, "test-key", Instant.now().minusSeconds(300)),
                    token(untrusted, "unknown-key", Instant.now().plusSeconds(300)))) {
                assertThatThrownBy(() -> authenticate(context, token)).isInstanceOf(InvalidBearerTokenException.class);
                assertFailure(context, token, HttpStatus.UNAUTHORIZED, AuthenticationFailureCodes.IAM_TOKEN_INVALID);
            }
        }
    }

    private void authenticate(AnnotationConfigWebApplicationContext context, String token) {
        new JwtAuthenticationProvider(context.getBean(JwtDecoder.class))
                .authenticate(new BearerTokenAuthenticationToken(token));
    }

    private void jwksResponse(AnnotationConfigWebApplicationContext context, HttpStatus status, String body)
            throws Exception {
        when(context.getBean(ServiceAddressResolver.class).intercept(any(), any(), any()))
                .thenAnswer(invocation -> new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status));
    }

    private void assertFailure(AnnotationConfigWebApplicationContext context, String token,
            HttpStatus expectedStatus, String expectedCode) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setServletPath("/api/v1/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forwarded = new AtomicBoolean();
        context.getBean("springSecurityFilterChain", Filter.class).doFilter(request, response,
                (downstreamRequest, downstreamResponse) -> forwarded.set(true));
        assertThat(forwarded).isFalse();
        assertThat(response.getStatus()).isEqualTo(expectedStatus.value());
        assertThat(response.getHeader(RequestHeaders.AUTH_FAILURE)).isEqualTo(expectedCode);
        assertThat(context.getBean(ObjectMapper.class).readTree(response.getContentAsByteArray())
                .get("code").asString()).isEqualTo(expectedCode);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(expectedStatus == HttpStatus.UNAUTHORIZED ? "Bearer" : null);
        assertThat(response.getContentAsString()).doesNotContain(token, "JWKS", "unreachable", "upstream");
    }

    private AnnotationConfigWebApplicationContext context() {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "rigour.gateway.security.enabled", "true",
                "rigour.gateway.security.issuer", ISSUER,
                "rigour.gateway.security.jwk-set-uri", ISSUER + "/oauth2/jwks",
                "rigour.gateway.security.current-token-validation-enabled", "true",
                "rigour.gateway.security.iam-current-token-uri", ISSUER + "/api/v1/token/current")));
        context.register(TestConfiguration.class, GatewaySecurityConfiguration.class);
        context.refresh();
        return context;
    }

    private static RSAKey publicKey(KeyPair pair) {
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID("test-key")
                .algorithm(JWSAlgorithm.RS256).build();
    }

    private String token(KeyPair pair, String keyId, Instant expiresAt) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(keyId).algorithm(JWSAlgorithm.RS256).build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER)
                .subject("019fb000-0000-7000-8000-000000000001").audience(List.of("rigour-api"))
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiresAt)
                .claims(values -> values.putAll(Map.of(
                        "tokenUse", "access", "principalScope", "TENANT",
                        "principalId", "019fb000-0000-7000-8000-000000000001",
                        "userId", "019fb000-0000-7000-8000-000000000001",
                        "sessionId", "019fb000-0000-7000-8000-000000000002",
                        "tenantId", "019fb000-0000-7000-8000-000000000003",
                        "sessionVersion", 0, "userSecurityVersion", 0, "tenantPolicyVersion", 0))).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims)).getTokenValue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().findAndAddModules().build(); }
        @Bean RestClient.Builder restClientBuilder() { return RestClient.builder(); }
        @Bean TrustedContextSigner signer() { return mock(TrustedContextSigner.class); }
        @Bean ServiceAddressResolver resolver() {
            ServiceAddressResolver resolver = mock(ServiceAddressResolver.class);
            // Spring Framework 7 composes the interceptor through this default method.
            doCallRealMethod().when(resolver).apply(any());
            return resolver;
        }
    }
}
