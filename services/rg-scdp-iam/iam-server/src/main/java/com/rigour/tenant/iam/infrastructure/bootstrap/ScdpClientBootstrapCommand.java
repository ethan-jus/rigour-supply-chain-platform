package com.rigour.tenant.iam.infrastructure.bootstrap;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/** 显式开启时幂等创建SCDP公开PKCE客户端；发现已有配置不一致则拒绝启动。 */
public final class ScdpClientBootstrapCommand implements ApplicationRunner {
    private final RegisteredClientRepository repository;
    private final IdentifierGenerator identifierGenerator;
    private final ScdpClientBootstrapProperties properties;

    public ScdpClientBootstrapCommand(RegisteredClientRepository repository,
            IdentifierGenerator identifierGenerator, ScdpClientBootstrapProperties properties) {
        this.repository = repository;
        this.identifierGenerator = identifierGenerator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        properties.validate();
        RegisteredClient existing = repository.findByClientId(properties.getClientId());
        if (existing != null) {
            boolean matches = existing.getClientAuthenticationMethods().equals(
                    java.util.Set.of(ClientAuthenticationMethod.NONE))
                    && existing.getClientSettings().isRequireProofKey()
                    && existing.getAuthorizationGrantTypes().equals(java.util.Set.of(
                            AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN))
                    && existing.getRedirectUris().equals(java.util.Set.of(properties.getRedirectUri()))
                    && existing.getPostLogoutRedirectUris().equals(java.util.Set.of(
                            properties.getPostLogoutRedirectUri()))
                    && existing.getScopes().equals(java.util.Set.of(OidcScopes.OPENID, OidcScopes.PROFILE));
            if (!matches) {
                throw new IllegalStateException("Existing SCDP OAuth client differs from reviewed bootstrap config");
            }
            return;
        }
        repository.save(RegisteredClient.withId(identifierGenerator.nextId().toString())
                .clientId(properties.getClientId()).clientIdIssuedAt(Instant.now())
                .clientName(properties.getClientName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(properties.getRedirectUri())
                .postLogoutRedirectUri(properties.getPostLogoutRedirectUri())
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .refreshTokenTimeToLive(Duration.ofDays(7)).reuseRefreshTokens(false)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256).build())
                .build());
    }
}
