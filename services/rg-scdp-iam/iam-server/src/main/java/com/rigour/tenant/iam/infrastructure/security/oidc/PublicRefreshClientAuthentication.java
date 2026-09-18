package com.rigour.tenant.iam.infrastructure.security.oidc;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/** 公开 PKCE 客户端的刷新入口；令牌归属、有效期及重放由标准授权提供者和存储层校验。 */
public final class PublicRefreshClientAuthentication implements AuthenticationConverter, AuthenticationProvider {
    private final RegisteredClientRepository clients;

    public PublicRefreshClientAuthentication(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || !"refresh_token".equals(request.getParameter("grant_type"))
                || request.getHeader("Authorization") != null || request.getParameter("client_secret") != null
                || request.getParameter("client_assertion") != null) {
            return null;
        }
        for (String parameter : Set.of("grant_type", "client_id", "refresh_token")) {
            String[] values = request.getParameterValues(parameter);
            if (values == null || values.length != 1 || !StringUtils.hasText(values[0])) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
            }
        }
        return new OAuth2ClientAuthenticationToken(request.getParameter("client_id"),
                ClientAuthenticationMethod.NONE, null, Map.of("grant_type", "refresh_token"));
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                || !"refresh_token".equals(token.getAdditionalParameters().get("grant_type"))) {
            return null;
        }
        RegisteredClient client = clients.findByClientId(token.getPrincipal().toString());
        if (!allowsRotatingRefresh(client)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }

    static boolean allowsRotatingRefresh(RegisteredClient client) {
        return client != null
                && client.getClientAuthenticationMethods().equals(Set.of(ClientAuthenticationMethod.NONE))
                && client.getClientSettings().isRequireProofKey()
                && client.getAuthorizationGrantTypes().containsAll(Set.of(
                        AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN))
                && !client.getTokenSettings().isReuseRefreshTokens();
    }
}
