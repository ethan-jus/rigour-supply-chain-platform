package com.rigour.gateway.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** 校验IAM Access Token的受众、用途、主体边界和版本声明结构。 */
public final class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error(
            "invalid_token", "IAM access token claims are invalid", null);

    private final List<String> requiredAudience;

    public AccessTokenClaimsValidator(List<String> requiredAudience) {
        this.requiredAudience = List.copyOf(requiredAudience);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            if (!token.getAudience().containsAll(requiredAudience)
                    || !"access".equals(token.getClaimAsString("tokenUse"))) {
                return failure();
            }
            UUID.fromString(requiredText(token, "sessionId"));
            UUID principalId = UUID.fromString(requiredText(token, "principalId"));
            nonNegativeVersion(token, "sessionVersion");
            nonNegativeVersion(token, "userSecurityVersion");
            String scope = requiredText(token, "principalScope");
            if ("TENANT".equals(scope)) {
                UUID.fromString(requiredText(token, "tenantId"));
                if (!principalId.equals(UUID.fromString(requiredText(token, "userId")))) {
                    return failure();
                }
                nonNegativeVersion(token, "tenantPolicyVersion");
            } else if ("PLATFORM".equals(scope)) {
                if (!principalId.equals(UUID.fromString(requiredText(token, "platformUserId")))
                        || token.hasClaim("tenantId") || token.hasClaim("userId")) {
                    return failure();
                }
            } else {
                return failure();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException exception) {
            return failure();
        }
    }

    private static String requiredText(Jwt token, String claim) {
        String value = token.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(claim + " is missing");
        }
        return value;
    }

    private static void nonNegativeVersion(Jwt token, String claim) {
        Object value = token.getClaims().get(claim);
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalArgumentException(claim + " is invalid");
        }
    }

    private static OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(INVALID);
    }
}
