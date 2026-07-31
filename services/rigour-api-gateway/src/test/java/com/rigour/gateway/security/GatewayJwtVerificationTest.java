package com.rigour.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayJwtVerificationTest {

    private static final String ISSUER = "https://iam.dev.rigour.local";

    @Test
    void acceptsOnlyTrustedSignatureIssuerAudienceAndAccessUse() throws Exception {
        KeyPair trusted = rsa();
        KeyPair untrusted = rsa();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) trusted.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new AccessTokenClaimsValidator(List.of("rigour-api"))));

        assertThat(decoder.decode(token(trusted, ISSUER, List.of("rigour-api"), "access")))
                .isNotNull();
        assertThatThrownBy(() -> decoder.decode(token(untrusted, ISSUER, List.of("rigour-api"), "access")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(trusted, "https://forged.example", List.of("rigour-api"), "access")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(trusted, ISSUER, List.of("wrong-api"), "access")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(trusted, ISSUER, List.of("rigour-api"), "id")))
                .isInstanceOf(JwtException.class);
    }

    private static String token(KeyPair keyPair, String issuer, List<String> audience, String tokenUse) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key").algorithm(JWSAlgorithm.RS256).build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).subject("019fb000-0000-7000-8000-000000000001")
                .audience(audience).issuedAt(now).expiresAt(now.plusSeconds(60))
                .claims(values -> values.putAll(Map.of(
                        "tokenUse", tokenUse,
                        "principalScope", "PLATFORM",
                        "principalId", "019fb000-0000-7000-8000-000000000001",
                        "platformUserId", "019fb000-0000-7000-8000-000000000001",
                        "sessionId", "019fb000-0000-7000-8000-000000000002",
                        "sessionVersion", 0,
                        "userSecurityVersion", 0)))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims)).getTokenValue();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
