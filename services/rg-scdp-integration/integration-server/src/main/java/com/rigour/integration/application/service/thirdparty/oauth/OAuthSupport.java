package com.rigour.integration.application.service.thirdparty.oauth;

import com.rigour.integration.domain.thirdparty.oauth.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

final class OAuthSupport {
    private OAuthSupport() {}

    static String nonce() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("授权摘要算法不可用");
        }
    }

    static boolean sameHash(String expected, String value) {
        return expected != null
                && value != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        hash(value).getBytes(StandardCharsets.US_ASCII));
    }

    static boolean leased(Instant until, Instant now) {
        return until != null && until.isAfter(now);
    }

    static Instant nextRefresh(OAuthToken token, Instant now) {
        if (token.expiresAt() == null || !token.expiresAt().isAfter(now.plusSeconds(30)))
            throw new OAuthFailure(
                    OAuthFailure.Kind.REAUTHORIZE, "TOKEN_EXPIRY", "平台令牌有效期不足，请重新授权");
        long ttl = Duration.between(now, token.expiresAt()).getSeconds();
        return token.expiresAt().minusSeconds(Math.min(300, Math.max(10, ttl / 5)));
    }

    static void bound(OAuthConfiguration c, String appKey, String fingerprint) {
        if (!Objects.equals(c.appKey(), appKey) || !Objects.equals(c.fingerprint(), fingerprint))
            throw new IllegalStateException("接入配置已变更，请重新发起授权");
    }
}
