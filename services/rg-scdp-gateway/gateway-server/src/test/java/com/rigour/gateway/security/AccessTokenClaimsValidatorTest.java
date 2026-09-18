package com.rigour.gateway.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenClaimsValidatorTest {

    private final AccessTokenClaimsValidator validator = new AccessTokenClaimsValidator(List.of("rigour-api"));

    @Test
    void acceptsWellFormedTenantAccessToken() {
        assertThat(validator.validate(tenantToken(Map.of())).hasErrors()).isFalse();
    }

    @Test
    void rejectsWrongAudienceIdTokenAndInvalidVersion() {
        assertThat(validator.validate(tenantToken(Map.of("aud", List.of("other-api")))).hasErrors()).isTrue();
        assertThat(validator.validate(tenantToken(Map.of("tokenUse", "id"))).hasErrors()).isTrue();
        assertThat(validator.validate(tenantToken(Map.of("sessionVersion", -1))).hasErrors()).isTrue();
    }

    @Test
    void rejectsMixedPlatformAndTenantIdentity() {
        Jwt jwt = token(Map.of(
                "aud", List.of("rigour-api"),
                "tokenUse", "access",
                "principalScope", "PLATFORM",
                "principalId", "019fb000-0000-7000-8000-000000000101",
                "platformUserId", "019fb000-0000-7000-8000-000000000101",
                "tenantId", "019fb000-0000-7000-8000-000000000102",
                "sessionId", "019fb000-0000-7000-8000-000000000103",
                "sessionVersion", 0,
                "userSecurityVersion", 0));
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    private static Jwt tenantToken(Map<String, Object> overrides) {
        java.util.HashMap<String, Object> claims = new java.util.HashMap<>(Map.of(
                "aud", List.of("rigour-api"),
                "tokenUse", "access",
                "principalScope", "TENANT",
                "principalId", "019fb000-0000-7000-8000-000000000001",
                "userId", "019fb000-0000-7000-8000-000000000001",
                "tenantId", "019fb000-0000-7000-8000-000000000002",
                "sessionId", "019fb000-0000-7000-8000-000000000003",
                "sessionVersion", 2,
                "userSecurityVersion", 3,
                "tenantPolicyVersion", 4));
        claims.putAll(overrides);
        return token(claims);
    }

    private static Jwt token(Map<String, Object> claims) {
        return new Jwt("signed-token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), claims);
    }
}
