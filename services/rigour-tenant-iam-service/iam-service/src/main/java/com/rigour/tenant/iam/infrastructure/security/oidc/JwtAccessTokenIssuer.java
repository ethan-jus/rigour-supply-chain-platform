package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.rigour.tenant.iam.application.port.out.AccessTokenIssuer;
import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

/** 使用IAM现行RSA签名密钥为飞书会话签发与OIDC一致的平台Access Token。 */
public final class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final IdentifierGenerator identifiers;
    private final String issuer;
    private final List<String> audience;

    public JwtAccessTokenIssuer(
            JwtEncoder encoder,
            IdentifierGenerator identifiers,
            OidcServerProperties serverProperties,
            OidcTokenProperties tokenProperties
    ) {
        this.encoder = encoder;
        this.identifiers = identifiers;
        this.issuer = serverProperties.requireIssuer();
        this.audience = tokenProperties.getAccessTokenAudience();
        if (audience.isEmpty() || audience.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("IAM access token audience cannot be empty");
        }
    }

    @Override
    public String issue(TenantClaims claims) {
        JwtClaimsSet claimSet = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(claims.userId().toString())
                .audience(audience)
                .issuedAt(claims.issuedAt())
                .notBefore(claims.issuedAt())
                .expiresAt(claims.expiresAt())
                .id(identifiers.nextId().toString())
                .claim("tokenUse", "access")
                .claim("sessionId", claims.sessionId().toString())
                .claim("principalScope", "TENANT")
                .claim("principalId", claims.userId().toString())
                .claim("tenantId", claims.tenantId().toString())
                .claim("userId", claims.userId().toString())
                .claim("sessionVersion", claims.sessionVersion())
                .claim("userSecurityVersion", claims.userSecurityVersion())
                .claim("tenantPolicyVersion", claims.tenantPolicyVersion())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claimSet)).getTokenValue();
    }
}
