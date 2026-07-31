package com.rigour.tenant.iam.infrastructure.security.oidc;

import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** IAM外部API的Access Token最小声明校验；角色与权限仍从数据库实时读取。 */
public final class IamAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error(
            "invalid_token", "IAM access token claims are invalid", null);
    private final List<String> audience;

    public IamAccessTokenValidator(List<String> audience) {
        this.audience = List.copyOf(audience);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            if (!token.getAudience().containsAll(audience)
                    || !"access".equals(token.getClaimAsString("tokenUse"))) {
                return OAuth2TokenValidatorResult.failure(INVALID);
            }
            UUID principalId = UUID.fromString(required(token, "principalId"));
            UUID.fromString(required(token, "sessionId"));
            String scope = required(token, "principalScope");
            if ("TENANT".equals(scope)) {
                UUID.fromString(required(token, "tenantId"));
                if (!principalId.equals(UUID.fromString(required(token, "userId")))) {
                    return OAuth2TokenValidatorResult.failure(INVALID);
                }
            } else if (!"PLATFORM".equals(scope)
                    || !principalId.equals(UUID.fromString(required(token, "platformUserId")))) {
                return OAuth2TokenValidatorResult.failure(INVALID);
            }
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException exception) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
    }

    private static String required(Jwt token, String claim) {
        String value = token.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(claim + " is missing");
        }
        return value;
    }
}
