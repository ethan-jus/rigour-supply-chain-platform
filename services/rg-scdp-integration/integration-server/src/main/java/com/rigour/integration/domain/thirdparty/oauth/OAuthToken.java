package com.rigour.integration.domain.thirdparty.oauth;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 平台令牌按原字符串流转；禁止默认日志或DTO输出凭据。 */
public record OAuthToken(
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        String tokenType,
        Instant refreshExpiresAt,
        Map<String, Object> metadata,
        Instant receivedAt) {
    public OAuthToken {
        metadata =
                metadata == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public OAuthToken(
            String access,
            String refresh,
            Instant expires,
            String tokenType,
            Instant refreshExpires,
            Map<String, Object> metadata) {
        this(access, refresh, expires, tokenType, refreshExpires, metadata, Instant.now());
    }

    public OAuthToken(String access, String refresh, Instant expires) {
        this(access, refresh, expires, null, null, Map.of());
    }

    @Override
    public String toString() {
        return "OAuthToken[REDACTED]";
    }
}
