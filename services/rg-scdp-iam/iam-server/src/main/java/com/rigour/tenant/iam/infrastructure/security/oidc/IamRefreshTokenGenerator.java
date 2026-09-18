package com.rigour.tenant.iam.infrastructure.security.oidc;

import java.time.Instant;
import java.util.Base64;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/** RFC 9700：公开客户端只签发强制轮换的刷新令牌，数据库仅保存摘要并检测重放。 */
public final class IamRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {
    private final OAuth2RefreshTokenGenerator delegate = new OAuth2RefreshTokenGenerator();
    private final Base64StringKeyGenerator random = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
        if (!context.getRegisteredClient().getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            return delegate.generate(context);
        }
        if (!PublicRefreshClientAuthentication.allowsRotatingRefresh(context.getRegisteredClient())
                || !(AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())
                    || AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType()))) {
            return null;
        }
        Instant issuedAt = Instant.now();
        return new OAuth2RefreshToken(random.generateKey(), issuedAt,
                issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive()));
    }
}
