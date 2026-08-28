package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder.GeocodeResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 为一次已完成逆地理编码的现场定位签发短期凭证。后续搜索、建店和打卡复用该凭证，
 * 不再重复调用高德，同时防止客户端伪造城市核验结果。
 */
@Component
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
class TemporaryCheckinLocationVerificationTokenService {

    private static final String VERSION = "v1";
    private static final String SIGNING_DOMAIN = "temporary-checkin-location-verification-v1\0";
    private static final int MAX_TOKEN_LENGTH = 8_192;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final UUID tenantId;
    private final byte[] signingKey;
    private final Duration tokenTtl;

    TemporaryCheckinLocationVerificationTokenService(
            TemporaryCheckinProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tenantId = properties.requireTenantId();
        this.signingKey = configuredKey(properties.getIdentitySigningKeyBase64(),
                properties.isIdentityEnforcementEnabled());
        this.tokenTtl = Duration.ofMinutes(properties.getMaxLocationAgeMinutes());
    }

    String issue(
            UUID salespersonId,
            String city,
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal accuracyMeters,
            Instant capturedAt,
            GeocodeResult geocode) {
        requireSigningKey();
        Instant issuedAt = clock.instant();
        Instant locationExpiresAt = capturedAt.plus(tokenTtl);
        Instant tokenExpiresAt = issuedAt.plus(tokenTtl);
        Instant expiresAt = locationExpiresAt.isBefore(tokenExpiresAt)
                ? locationExpiresAt : tokenExpiresAt;
        LocationPayload payload = new LocationPayload(
                tenantId, salespersonId, city, longitude, latitude, accuracyMeters, capturedAt,
                geocode, issuedAt, expiresAt);
        try {
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String unsigned = VERSION + "." + encodedPayload;
            return unsigned + "." + sign(unsigned);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("现场定位核验凭证生成失败", exception);
        }
    }

    GeocodeResult verify(
            String token,
            UUID salespersonId,
            String city,
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal accuracyMeters,
            Instant capturedAt) {
        requireSigningKey();
        LocationPayload payload = parse(token);
        Instant now = clock.instant();
        boolean bindingMatches = tenantId.equals(payload.tenantId())
                && salespersonId.equals(payload.salespersonId())
                && city.equals(payload.city())
                && sameNumber(longitude, payload.longitude())
                && sameNumber(latitude, payload.latitude())
                && sameNumber(accuracyMeters, payload.accuracyMeters())
                && capturedAt.equals(payload.capturedAt())
                && payload.issuedAt() != null
                && payload.expiresAt() != null
                && !payload.issuedAt().isAfter(now.plusSeconds(30))
                && now.isBefore(payload.expiresAt())
                && !payload.expiresAt().isAfter(payload.issuedAt().plus(tokenTtl))
                && payload.geocode() != null
                && ("RESOLVED".equals(payload.geocode().status())
                    || "FAILED".equals(payload.geocode().status())
                    || "KEY_MISSING".equals(payload.geocode().status()));
        if (!bindingMatches) throw invalidProof();
        return payload.geocode();
    }

    private LocationPayload parse(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw invalidProof();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) throw invalidProof();
        String unsigned = parts[0] + "." + parts[1];
        if (!constantEquals(sign(unsigned), parts[2])) throw invalidProof();
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            return objectMapper.readValue(json, LocationPayload.class);
        } catch (RuntimeException exception) {
            throw invalidProof();
        }
    }

    private String sign(String unsigned) {
        byte[] domain = SIGNING_DOMAIN.getBytes(StandardCharsets.UTF_8);
        byte[] value = unsigned.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[domain.length + value.length];
        System.arraycopy(domain, 0, input, 0, domain.length);
        System.arraycopy(value, 0, input, domain.length, value.length);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256不可用", exception);
        }
    }

    private void requireSigningKey() {
        if (signingKey == null) {
            throw TemporaryCheckinException.conflict("现场定位核验凭证尚未完成服务器配置");
        }
    }

    private static byte[] configuredKey(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalStateException(
                        "rigour.sales.temporary-checkin.identity-signing-key-base64未配置");
            }
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value.trim());
            if (decoded.length < 32) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "rigour.sales.temporary-checkin.identity-signing-key-base64必须是至少32字节Base64",
                    exception);
        }
    }

    private static boolean sameNumber(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) == 0;
    }

    private static boolean constantEquals(String first, String second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private static TemporaryCheckinException invalidProof() {
        return TemporaryCheckinException.badRequest(
                "现场定位核验已过期或与当前定位不一致，请重新获取当前位置");
    }

    private record LocationPayload(
            UUID tenantId,
            UUID salespersonId,
            String city,
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal accuracyMeters,
            Instant capturedAt,
            GeocodeResult geocode,
            Instant issuedAt,
            Instant expiresAt) { }
}
