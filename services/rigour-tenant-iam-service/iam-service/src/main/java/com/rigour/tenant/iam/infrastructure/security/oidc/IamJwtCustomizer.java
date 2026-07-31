package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.rigour.tenant.iam.infrastructure.security.oidc.IamTokenClaimsResolver.Claims;
import java.util.List;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/** 为Access Token和ID Token写入最小稳定身份声明，不把完整角色或数据范围塞进JWT。 */
public final class IamJwtCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final OAuth2TokenType ID_TOKEN_TYPE = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

    private final IamTokenClaimsResolver claimsResolver;
    private final List<String> accessTokenAudience;

    public IamJwtCustomizer(IamTokenClaimsResolver claimsResolver, List<String> accessTokenAudience) {
        if (accessTokenAudience == null || accessTokenAudience.isEmpty()
                || accessTokenAudience.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("accessTokenAudience cannot be empty");
        }
        this.claimsResolver = claimsResolver;
        this.accessTokenAudience = List.copyOf(accessTokenAudience);
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                && !ID_TOKEN_TYPE.equals(context.getTokenType())) {
            return;
        }
        Claims claims = claimsResolver.resolve(context.getPrincipal());
        context.getClaims()
                .claim("sessionId", claims.sessionId().toString())
                .claim("principalScope", claims.principalScope())
                .claim("principalId", claims.principalId().toString())
                .claim("sessionVersion", claims.sessionVersion())
                .claim("userSecurityVersion", claims.userSecurityVersion());
        if (claims.tenantId() != null) {
            context.getClaims()
                    .claim("tenantId", claims.tenantId().toString())
                    .claim("userId", claims.principalId().toString())
                    .claim("tenantPolicyVersion", claims.tenantPolicyVersion());
        } else {
            context.getClaims().claim("platformUserId", claims.principalId().toString());
        }
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            context.getClaims().audience(accessTokenAudience).claim("tokenUse", "access");
        } else {
            context.getClaims().claim("tokenUse", "id");
        }
    }
}
