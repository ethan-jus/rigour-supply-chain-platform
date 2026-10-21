package com.rigour.tenant.iam.infrastructure.bootstrap;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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

/** 只创建独立移动客户端；既有配置漂移时拒绝覆盖，保留所有浏览器客户端。 */
public final class SalesAppClientBootstrapCommand implements ApplicationRunner {
    private final RegisteredClientRepository repository;
    private final IdentifierGenerator identifiers;
    private final SalesAppClientBootstrapProperties properties;
    private final boolean developmentEnvironment;

    public SalesAppClientBootstrapCommand(RegisteredClientRepository repository, IdentifierGenerator identifiers,
            SalesAppClientBootstrapProperties properties, boolean developmentEnvironment) {
        this.repository = repository;
        this.identifiers = identifiers;
        this.properties = properties;
        this.developmentEnvironment = developmentEnvironment;
    }

    @Override
    public void run(ApplicationArguments args) {
        properties.validate(developmentEnvironment);
        RegisteredClient existing = repository.findByClientId(properties.getClientId());
        TokenSettings tokenSettings = TokenSettings.builder()
                .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                .accessTokenTimeToLive(Duration.ofMinutes(15))
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .refreshTokenTimeToLive(Duration.ofDays(7)).reuseRefreshTokens(false)
                .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256).build();
        if (existing != null) {
            if (!existing.getClientAuthenticationMethods().equals(Set.of(ClientAuthenticationMethod.NONE))
                    || existing.getClientSecret() != null
                    || !existing.getAuthorizationGrantTypes().equals(Set.of(
                            AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN))
                    || !existing.getRedirectUris().equals(Set.of(properties.getRedirectUri()))
                    || !existing.getPostLogoutRedirectUris().isEmpty()
                    || !existing.getScopes().equals(Set.of(OidcScopes.PROFILE))
                    || !existing.getClientSettings().isRequireProofKey()
                    || existing.getClientSettings().isRequireAuthorizationConsent()
                    || !existing.getTokenSettings().getSettings().equals(tokenSettings.getSettings())) {
                throw new IllegalStateException("Existing Sales App OAuth client differs from reviewed bootstrap config");
            }
            return;
        }
        repository.save(RegisteredClient.withId(identifiers.nextId().toString())
                .clientId(properties.getClientId()).clientIdIssuedAt(Instant.now())
                .clientName(properties.getClientName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(properties.getRedirectUri())
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder().requireProofKey(true)
                        .requireAuthorizationConsent(false).build())
                .tokenSettings(tokenSettings).build());
    }
}
