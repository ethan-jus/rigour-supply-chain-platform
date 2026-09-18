package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamRefreshTokenGenerator;
import com.rigour.tenant.iam.infrastructure.security.oidc.PublicRefreshClientAuthentication;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;

class PublicRefreshClientAuthenticationTest {
    private RegisteredClient.Builder client() {
        return RegisteredClient.withId("test").clientId("browser")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://example.test/callback")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false)
                        .refreshTokenTimeToLive(Duration.ofHours(8)).build());
    }

    @Test void onlyExplicitPkceRotatingClientsCanUseRefresh() {
        var allowed = client().build();
        var rejected = List.of(
                client().clientSettings(ClientSettings.builder().requireProofKey(false).build()).build(),
                client().tokenSettings(TokenSettings.builder().reuseRefreshTokens(true).build()).build(),
                client().authorizationGrantTypes(types -> types.remove(AuthorizationGrantType.REFRESH_TOKEN)).build(),
                client().clientAuthenticationMethods(methods -> { methods.clear(); methods.add(ClientAuthenticationMethod.CLIENT_SECRET_BASIC); }).build());
        for (var registered : rejected) {
            var authentication = new PublicRefreshClientAuthentication(new InMemoryRegisteredClientRepository(registered));
            assertThatThrownBy(() -> authentication.authenticate(refreshRequest()))
                    .isInstanceOf(OAuth2AuthenticationException.class);
        }
        var authentication = new PublicRefreshClientAuthentication(new InMemoryRegisteredClientRepository(allowed));
        assertThat(authentication.authenticate(refreshRequest()).isAuthenticated()).isTrue();
        assertThat(authentication.authenticate(new OAuth2ClientAuthenticationToken("browser",
                ClientAuthenticationMethod.NONE, null, Map.of("code_verifier", "verifier")))).isNull();
        assertThatThrownBy(() -> authentication.authenticate(new OAuth2ClientAuthenticationToken("unknown",
                ClientAuthenticationMethod.NONE, null, Map.of("grant_type", "refresh_token"))))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test void converterRejectsAmbiguousParametersAndLeavesOtherMethodsToSpring() {
        var authentication = new PublicRefreshClientAuthentication(new InMemoryRegisteredClientRepository(client().build()));
        var request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setParameter("grant_type", "refresh_token");
        request.setParameter("client_id", "browser");
        request.setParameter("refresh_token", "opaque");
        assertThat(authentication.convert(request)).isNotNull();
        request.setParameter("client_id", "browser", "other");
        assertThatThrownBy(() -> authentication.convert(request)).isInstanceOf(OAuth2AuthenticationException.class);
        request.setParameter("client_id", "browser");
        request.removeParameter("refresh_token");
        assertThatThrownBy(() -> authentication.convert(request)).isInstanceOf(OAuth2AuthenticationException.class);
        request.addHeader("Authorization", "Basic credentials");
        assertThat(authentication.convert(request)).isNull();
    }

    @Test void generatorIssuesDistinctOpaqueTokensOnlyForEligiblePublicClients() {
        var generator = new IamRefreshTokenGenerator();
        var context = DefaultOAuth2TokenContext.builder().registeredClient(client().build())
                .tokenType(OAuth2TokenType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).build();
        var first = generator.generate(context);
        var second = generator.generate(context);
        assertThat(first).isNotNull();
        assertThat(first.getTokenValue()).matches("[A-Za-z0-9_-]{128}").isNotEqualTo(second.getTokenValue());
        assertThat(Duration.between(first.getIssuedAt(), first.getExpiresAt())).isEqualTo(Duration.ofHours(8));
        assertThat(generator.generate(DefaultOAuth2TokenContext.builder().registeredClient(client().build())
                .tokenType(OAuth2TokenType.ACCESS_TOKEN).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).build())).isNull();
        assertThat(generator.generate(DefaultOAuth2TokenContext.builder().registeredClient(
                client().tokenSettings(TokenSettings.builder().reuseRefreshTokens(true).build()).build())
                .tokenType(OAuth2TokenType.REFRESH_TOKEN).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).build())).isNull();
    }

    private OAuth2ClientAuthenticationToken refreshRequest() {
        return new OAuth2ClientAuthenticationToken("browser", ClientAuthenticationMethod.NONE, null,
                Map.of("grant_type", "refresh_token"));
    }
}
