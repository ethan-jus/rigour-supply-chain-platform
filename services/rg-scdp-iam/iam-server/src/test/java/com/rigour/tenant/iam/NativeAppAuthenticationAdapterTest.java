package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.tenant.iam.application.service.auth.NativeAppAuthentication;
import com.rigour.tenant.iam.api.controller.auth.NativeAppAuthController;
import com.rigour.tenant.iam.infrastructure.security.session.IamAuthenticationDetails;
import com.rigour.tenant.iam.infrastructure.security.session.IamSessionLogoutHandler;
import com.rigour.tenant.iam.infrastructure.security.session.NativeAppAuthenticationAdapter;
import com.rigour.tenant.iam.infrastructure.security.session.NativeAppAuthenticationProperties;
import com.rigour.tenant.iam.infrastructure.security.session.PasswordAuthenticationProperties;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

class NativeAppAuthenticationAdapterTest {
    @Test
    void loginControllerIsAbsentUnlessBothNativeLoginAndOidcAreEnabled() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(NativeAppAuthController.class)
                .withBean(NativeAppAuthentication.class, () -> mock(NativeAppAuthentication.class));
        runner.withPropertyValues("rigour.iam.oidc.server.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(NativeAppAuthController.class));
        runner.withPropertyValues("rigour.iam.authentication.native-app.enabled=false", "rigour.iam.oidc.server.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(NativeAppAuthController.class));
        runner.withPropertyValues("rigour.iam.authentication.native-app.enabled=true", "rigour.iam.oidc.server.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NativeAppAuthController.class));
    }

    @Test
    void disabledByDefaultAndRequiresExplicitDedicatedClientAllowlist() {
        var properties = new NativeAppAuthenticationProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setAllowedClientIds(Set.of("rigour-scdp-browser"));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setAllowedClientIds(Set.of("rigour-sales-app-android", "rigour-sales-app-ios-dev"));
        properties.validate();
    }

    @Test
    void codePersistenceFailureRevokesNewSessionErasesPasswordAndClearsProtocolContext() {
        var manager = mock(AuthenticationManager.class);
        var clients = mock(RegisteredClientRepository.class);
        var authorizations = mock(OAuth2AuthorizationService.class);
        var consents = mock(OAuth2AuthorizationConsentService.class);
        var logout = mock(IamSessionLogoutHandler.class);
        var properties = new NativeAppAuthenticationProperties();
        properties.setAllowedClientIds(Set.of("rigour-sales-app-android"));
        var passwordProperties = new PasswordAuthenticationProperties();
        passwordProperties.setDefaultTenantCode("test-company");
        var client = RegisteredClient.withId(UUID.randomUUID().toString()).clientId("rigour-sales-app-android")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("com.rigour.workbench://auth/callback").scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).build()).build();
        when(clients.findByClientId(client.getClientId())).thenReturn(client);
        UUID user = UUID.randomUUID();
        var principal = UsernamePasswordAuthenticationToken.authenticated(user.toString(), null, List.of());
        principal.setDetails(IamAuthenticationDetails.create(UUID.randomUUID(), "TENANT", user, UUID.randomUUID(), 0));
        when(manager.authenticate(any())).thenReturn(principal);
        doThrow(new IllegalStateException("private database detail")).when(authorizations).save(any());
        var adapter = new NativeAppAuthenticationAdapter(manager, clients, authorizations, consents, properties,
                passwordProperties, AuthorizationServerSettings.builder().issuer("https://iam.example.test").build(), logout, mock(JdbcTemplate.class));
        char[] password = "do-not-log-me".toCharArray();
        var command = new NativeAppAuthentication.LoginCommand(client.getClientId(), "com.rigour.workbench://auth/callback",
                "A".repeat(43), "S256", "a".repeat(32), "employee", password, null, null, null, null);
        assertThat(command.toString()).doesNotContain("do-not-log-me");
        assertThatThrownBy(() -> adapter.login(command)).isInstanceOf(NativeAppAuthentication.Failure.class)
                .hasMessage("UNAVAILABLE");
        verify(authorizations).save(any());
        verify(logout).logout(null, null, principal);
        assertThat(password).containsOnly('\0');
        assertThat(AuthorizationServerContextHolder.getContext()).isNull();
    }
}
