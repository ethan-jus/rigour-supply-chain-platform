package com.rigour.tenant.iam.infrastructure.security.session;

import com.rigour.tenant.iam.application.service.auth.NativeAppAuthentication;
import com.rigour.tenant.iam.domain.model.session.AuthSession;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/** 无浏览器会话副作用的适配层；复用密码认证、标准授权码 provider 和会话撤销。 */
public final class NativeAppAuthenticationAdapter implements NativeAppAuthentication {
    private final AuthenticationManager authenticationManager;
    private final RegisteredClientRepository clients;
    private final OAuth2AuthorizationCodeRequestAuthenticationProvider authorizationProvider;
    private final NativeAppAuthenticationProperties properties;
    private final PasswordAuthenticationProperties passwordProperties;
    private final AuthorizationServerSettings serverSettings;
    private final IamSessionLogoutHandler logoutHandler;
    private final JdbcTemplate jdbcTemplate;

    public NativeAppAuthenticationAdapter(AuthenticationManager authenticationManager,
            RegisteredClientRepository clients, OAuth2AuthorizationService authorizations,
            OAuth2AuthorizationConsentService consents, NativeAppAuthenticationProperties properties,
            PasswordAuthenticationProperties passwordProperties, AuthorizationServerSettings serverSettings,
            IamSessionLogoutHandler logoutHandler, JdbcTemplate jdbcTemplate) {
        properties.validate();
        this.authenticationManager = authenticationManager;
        this.clients = clients;
        this.authorizationProvider = new OAuth2AuthorizationCodeRequestAuthenticationProvider(clients, authorizations, consents);
        this.properties = properties;
        this.passwordProperties = passwordProperties;
        this.serverSettings = serverSettings;
        this.logoutHandler = logoutHandler;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LoginResult login(LoginCommand command) {
        Authentication principal = null;
        AuthorizationServerContext previous = AuthorizationServerContextHolder.getContext();
        try {
            validate(command);
            RegisteredClient client = clients.findByClientId(command.clientId());
            requireNativeClient(client, command.redirectUri());
            String tenantCode = text(command.tenantCode());
            if (tenantCode == null) tenantCode = text(passwordProperties.getDefaultTenantCode());
            if (tenantCode == null || tenantCode.length() > 32) throw new Failure(Kind.INVALID_REQUEST);
            principal = authenticationManager.authenticate(new IamLoginAuthenticationToken(
                    AuthSession.PrincipalScope.TENANT, tenantCode, command.username().strip(), command.password(),
                    AuthSession.ClientType.NATIVE_APP, command.deviceName(), null,
                    command.userAgentHash(), command.ipAddress()));
            AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
                @Override public String getIssuer() { return serverSettings.getIssuer(); }
                @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return serverSettings; }
            });
            var authorized = authorizationProvider.authenticate(new OAuth2AuthorizationCodeRequestAuthenticationToken(
                    serverSettings.getIssuer() + serverSettings.getAuthorizationEndpoint(), client.getClientId(),
                    principal, command.redirectUri(), command.state(), Set.of(OidcScopes.PROFILE),
                    Map.of("code_challenge", command.codeChallenge(), "code_challenge_method", "S256")));
            if (!(authorized instanceof OAuth2AuthorizationCodeRequestAuthenticationToken result)
                    || result.getAuthorizationCode() == null) throw new Failure(Kind.UNAVAILABLE);
            var code = result.getAuthorizationCode();
            return new LoginResult(code.getTokenValue(), Duration.between(code.getIssuedAt(), code.getExpiresAt()).toSeconds(), result.getState());
        } catch (BadCredentialsException invalid) {
            throw new Failure(Kind.AUTHENTICATION_FAILED);
        } catch (Failure failure) {
            if (principal != null) logoutHandler.logout(null, null, principal);
            throw failure;
        } catch (RuntimeException unavailable) {
            // 密码失败计数已由 provider 独立提交；发码失败则撤销本次刚创建的会话。
            if (principal != null) logoutHandler.logout(null, null, principal);
            throw new Failure(Kind.UNAVAILABLE);
        } finally {
            if (command.password() != null) Arrays.fill(command.password(), '\0');
            if (previous == null) AuthorizationServerContextHolder.resetContext();
            else AuthorizationServerContextHolder.setContext(previous);
        }
    }

    @Override
    public void logout(UUID sessionId, UUID userId, UUID tenantId) {
        Integer owned = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM iam_auth_session
                WHERE id=? AND principal_id=? AND tenant_id=? AND principal_scope='TENANT' AND client_type='NATIVE_APP'
                """, Integer.class, UuidBinaryCodec.encode(sessionId), UuidBinaryCodec.encode(userId), UuidBinaryCodec.encode(tenantId));
        if (owned == null || owned != 1) throw new Failure(Kind.FORBIDDEN);
        logoutHandler.logoutSession(sessionId);
    }

    private void validate(LoginCommand command) {
        if (command.clientId() == null || !properties.getAllowedClientIds().contains(command.clientId()))
            throw new Failure(Kind.INVALID_CLIENT);
        if (text(command.username()) == null || command.username().strip().length() > 64
                || command.password() == null || command.password().length == 0 || command.password().length > 1024
                || command.redirectUri() == null || command.redirectUri().length() > 512
                || !"S256".equals(command.codeChallengeMethod())
                || command.codeChallenge() == null || !command.codeChallenge().matches("[A-Za-z0-9_-]{43}")
                || command.state() == null || !command.state().matches("[A-Za-z0-9_-]{32,128}")
                || (command.deviceName() != null && command.deviceName().length() > 128)) {
            throw new Failure(Kind.INVALID_REQUEST);
        }
    }

    private static void requireNativeClient(RegisteredClient client, String redirectUri) {
        if (client == null || !client.getClientAuthenticationMethods().equals(Set.of(ClientAuthenticationMethod.NONE))
                || client.getClientSecret() != null || !client.getClientSettings().isRequireProofKey()
                || client.getClientSettings().isRequireAuthorizationConsent()
                || !client.getAuthorizationGrantTypes().equals(Set.of(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN))
                || !client.getScopes().equals(Set.of(OidcScopes.PROFILE))
                || !client.getRedirectUris().equals(Set.of(redirectUri))
                || client.getTokenSettings().isReuseRefreshTokens()) {
            throw new Failure(Kind.INVALID_CLIENT);
        }
    }

    private static String text(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
