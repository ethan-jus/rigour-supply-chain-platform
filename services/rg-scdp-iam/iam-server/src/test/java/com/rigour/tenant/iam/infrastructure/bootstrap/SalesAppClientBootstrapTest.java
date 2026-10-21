package com.rigour.tenant.iam.infrastructure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

class SalesAppClientBootstrapTest {
    @Test
    void disabledByDefaultAndFormalCallbackIsExact() {
        var properties = new SalesAppClientBootstrapProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThatCode(() -> properties.validate(false)).doesNotThrowAnyException();
        for (String uri : new String[]{
                "http://localhost:5173/", "http://192.168.1.2/callback", "https://example.com/callback",
                "com.other://auth/callback", "com.rigour.workbench://auth/callback?next=evil",
                "com.rigour.workbench://auth/callback#fragment", "com.rigour.workbench://auth/callback/",
                "com.rigour.workbench://user@auth/callback", "com.rigour.workbench://auth:80/callback"}) {
            properties.setRedirectUri(uri);
            assertThatThrownBy(() -> properties.validate(true)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void sharedDebugSchemeRequiresDevProfileOptInAndIndependentDevClient() {
        var properties = debugProperties();
        assertThatCode(() -> properties.validate(true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> properties.validate(false)).isInstanceOf(IllegalStateException.class);
        properties.setAllowDebugBase(false);
        assertThatThrownBy(() -> properties.validate(true)).isInstanceOf(IllegalStateException.class);
        properties.setAllowDebugBase(true);
        properties.setClientId("rigour-sales-app-android");
        assertThatThrownBy(() -> properties.validate(true)).isInstanceOf(IllegalStateException.class);
        properties.setClientId("rigour-scdp-browser");
        assertThatThrownBy(() -> properties.validate(true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsProfileOnlyPublicPkceClientWithRotatingRefresh() {
        var repository = mock(RegisteredClientRepository.class);
        run(repository, debugProperties(), true);
        var saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repository).save(saved.capture());
        var client = saved.getValue();
        assertThat(client.getClientId()).isEqualTo("rigour-sales-app-android-dev");
        assertThat(client.getClientSecret()).isNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
                AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getScopes()).containsExactly("profile");
        assertThat(client.getRedirectUris()).containsExactly(SalesAppClientBootstrapProperties.DEBUG_CALLBACK);
        assertThat(client.getPostLogoutRedirectUris()).isEmpty();
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void rerunDoesNotOverwriteExistingClient() {
        var properties = debugProperties();
        var existing = createdClient();
        var repository = mock(RegisteredClientRepository.class);
        when(repository.findByClientId(properties.getClientId())).thenReturn(existing);
        run(repository, properties, true);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void configurationDriftFailsInsteadOfBroadeningExistingClient() {
        var properties = debugProperties();
        var original = createdClient();
        var variants = new RegisteredClient[]{
            RegisteredClient.from(original).redirectUri("com.other://auth/callback").build(),
            RegisteredClient.from(original).scope("openid").build(),
            RegisteredClient.from(original).clientSettings(ClientSettings.builder().requireProofKey(false).build()).build(),
            RegisteredClient.from(original).tokenSettings(TokenSettings.builder().reuseRefreshTokens(true).build()).build(),
            RegisteredClient.from(original).tokenSettings(TokenSettings.builder().reuseRefreshTokens(false)
                    .accessTokenTimeToLive(Duration.ofHours(1)).build()).build()
        };
        for (RegisteredClient existing : variants) {
            var repository = mock(RegisteredClientRepository.class);
            when(repository.findByClientId(properties.getClientId())).thenReturn(existing);
            assertThatThrownBy(() -> run(repository, properties, true)).isInstanceOf(IllegalStateException.class);
            verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    private RegisteredClient createdClient() {
        var repository = mock(RegisteredClientRepository.class);
        run(repository, debugProperties(), true);
        var saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    private SalesAppClientBootstrapProperties debugProperties() {
        var properties = new SalesAppClientBootstrapProperties();
        properties.setClientId("rigour-sales-app-android-dev");
        properties.setRedirectUri(SalesAppClientBootstrapProperties.DEBUG_CALLBACK);
        properties.setAllowDebugBase(true);
        return properties;
    }

    private void run(RegisteredClientRepository repository, SalesAppClientBootstrapProperties properties, boolean dev) {
        new SalesAppClientBootstrapCommand(repository, UUID::randomUUID, properties, dev)
                .run(new DefaultApplicationArguments());
    }
}
