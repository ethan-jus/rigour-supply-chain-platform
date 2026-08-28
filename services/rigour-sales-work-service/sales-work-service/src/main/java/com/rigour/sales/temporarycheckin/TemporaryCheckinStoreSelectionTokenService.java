package com.rigour.sales.temporarycheckin;

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

/** 为一次高德搜索候选签发短期、无状态的服务端凭证。 */
@Component
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
class TemporaryCheckinStoreSelectionTokenService {

    private static final String VERSION = "v1";
    private static final String MANUAL_VERSION = "m1";
    private static final String SIGNING_DOMAIN = "temporary-checkin-store-selection-v1\0";
    private static final int MAX_TOKEN_LENGTH = 8_192;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final UUID tenantId;
    private final byte[] signingKey;
    private final Duration tokenTtl;
    private final int locationToleranceMeters;

    TemporaryCheckinStoreSelectionTokenService(
            TemporaryCheckinProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tenantId = properties.requireTenantId();
        this.signingKey = configuredKey(properties.getIdentitySigningKeyBase64(),
                properties.isIdentityEnforcementEnabled());
        this.tokenTtl = Duration.ofMinutes(properties.getMaxLocationAgeMinutes());
        this.locationToleranceMeters = properties.getMaxCheckinDistanceMeters();
    }

    String issue(
            UUID salespersonId,
            String city,
            BigDecimal searchLongitude,
            BigDecimal searchLatitude,
            BigDecimal searchAccuracyMeters,
            Instant searchCapturedAt,
            Candidate candidate) {
        requireSigningKey();
        Instant issuedAt = clock.instant();
        SelectionPayload payload = new SelectionPayload(
                tenantId, city, salespersonId,
                searchLongitude, searchLatitude, searchAccuracyMeters, searchCapturedAt,
                candidate, issuedAt, issuedAt.plus(tokenTtl));
        try {
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String unsigned = VERSION + "." + encodedPayload;
            return unsigned + "." + sign(unsigned);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("高德门店候选凭证生成失败", exception);
        }
    }

    Candidate verify(
            String token,
            UUID salespersonId,
            String city,
            BigDecimal currentLongitude,
            BigDecimal currentLatitude) {
        requireSigningKey();
        SelectionPayload payload = parse(token);
        Instant now = clock.instant();
        boolean bindingMatches = tenantId.equals(payload.tenantId())
                && city.equals(payload.city())
                && salespersonId.equals(payload.salespersonId())
                && payload.issuedAt() != null
                && payload.expiresAt() != null
                && !payload.issuedAt().isAfter(now.plusSeconds(30))
                && now.isBefore(payload.expiresAt())
                && !payload.expiresAt().isAfter(payload.issuedAt().plus(tokenTtl))
                && payload.searchLongitude() != null
                && payload.searchLatitude() != null
                && payload.searchAccuracyMeters() != null
                && payload.searchCapturedAt() != null
                && payload.candidate() != null;
        if (!bindingMatches || distanceMeters(currentLatitude, currentLongitude,
                payload.searchLatitude(), payload.searchLongitude()) > locationToleranceMeters) {
            throw invalidSelection();
        }
        Candidate candidate = payload.candidate();
        if (blank(candidate.poiId()) || blank(candidate.name())
                || candidate.longitude() == null || candidate.latitude() == null
                || (blank(candidate.cityName()) && blank(candidate.adcode()))) {
            throw invalidSelection();
        }
        return candidate;
    }

    String issueManual(
            UUID clientStoreId,
            UUID salespersonId,
            String city,
            BigDecimal searchLongitude,
            BigDecimal searchLatitude,
            BigDecimal searchAccuracyMeters,
            Instant searchCapturedAt,
            String lookupStatus) {
        requireSigningKey();
        if (!"EMPTY".equals(lookupStatus) && !"UNAVAILABLE".equals(lookupStatus)) {
            throw new IllegalArgumentException("人工建店凭证只能用于无结果或上游不可用状态");
        }
        Instant issuedAt = clock.instant();
        ManualPayload payload = new ManualPayload(
                tenantId, clientStoreId, city, salespersonId,
                searchLongitude, searchLatitude, searchAccuracyMeters, searchCapturedAt,
                lookupStatus, issuedAt, issuedAt.plus(tokenTtl));
        try {
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String unsigned = MANUAL_VERSION + "." + encodedPayload;
            return unsigned + "." + sign(unsigned);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("人工建店凭证生成失败", exception);
        }
    }

    void verifyManual(
            String token,
            UUID clientStoreId,
            UUID salespersonId,
            String city,
            BigDecimal currentLongitude,
            BigDecimal currentLatitude,
            BigDecimal currentAccuracyMeters,
            Instant currentCapturedAt) {
        requireSigningKey();
        ManualPayload payload = parseManual(token);
        Instant now = clock.instant();
        boolean bindingMatches = tenantId.equals(payload.tenantId())
                && clientStoreId.equals(payload.clientStoreId())
                && city.equals(payload.city())
                && salespersonId.equals(payload.salespersonId())
                && sameNumber(currentLongitude, payload.searchLongitude())
                && sameNumber(currentLatitude, payload.searchLatitude())
                && sameNumber(currentAccuracyMeters, payload.searchAccuracyMeters())
                && currentCapturedAt.equals(payload.searchCapturedAt())
                && ("EMPTY".equals(payload.lookupStatus())
                    || "UNAVAILABLE".equals(payload.lookupStatus()))
                && payload.issuedAt() != null
                && payload.expiresAt() != null
                && !payload.issuedAt().isAfter(now.plusSeconds(30))
                && now.isBefore(payload.expiresAt())
                && !payload.expiresAt().isAfter(payload.issuedAt().plus(tokenTtl));
        if (!bindingMatches) throw invalidManualSelection();
    }

    private SelectionPayload parse(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw invalidSelection();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) throw invalidSelection();
        String unsigned = parts[0] + "." + parts[1];
        if (!constantEquals(sign(unsigned), parts[2])) throw invalidSelection();
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            return objectMapper.readValue(json, SelectionPayload.class);
        } catch (RuntimeException exception) {
            throw invalidSelection();
        }
    }

    private ManualPayload parseManual(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw invalidManualSelection();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !MANUAL_VERSION.equals(parts[0])) {
            throw invalidManualSelection();
        }
        String unsigned = parts[0] + "." + parts[1];
        if (!constantEquals(sign(unsigned), parts[2])) throw invalidManualSelection();
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            return objectMapper.readValue(json, ManualPayload.class);
        } catch (RuntimeException exception) {
            throw invalidManualSelection();
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
            throw TemporaryCheckinException.conflict("高德门店候选凭证尚未完成服务器配置");
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

    private static boolean constantEquals(String first, String second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean sameNumber(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) == 0;
    }

    private static TemporaryCheckinException invalidSelection() {
        return TemporaryCheckinException.badRequest("高德门店候选已过期或与当前现场不一致，请重新搜索选择");
    }

    private static TemporaryCheckinException invalidManualSelection() {
        return TemporaryCheckinException.badRequest(
                "人工建店凭证已过期或与当前现场不一致，请重新搜索后再手工录入");
    }

    private static double distanceMeters(
            BigDecimal latitude1,
            BigDecimal longitude1,
            BigDecimal latitude2,
            BigDecimal longitude2) {
        double lat1 = Math.toRadians(latitude1.doubleValue());
        double lat2 = Math.toRadians(latitude2.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(longitude2.doubleValue() - longitude1.doubleValue());
        double value = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 6_371_000d * 2d * Math.atan2(Math.sqrt(value), Math.sqrt(1d - value));
    }

    record Candidate(
            String poiId,
            String name,
            String address,
            BigDecimal longitude,
            BigDecimal latitude,
            String cityName,
            String adcode) { }

    private record SelectionPayload(
            UUID tenantId,
            String city,
            UUID salespersonId,
            BigDecimal searchLongitude,
            BigDecimal searchLatitude,
            BigDecimal searchAccuracyMeters,
            Instant searchCapturedAt,
            Candidate candidate,
            Instant issuedAt,
            Instant expiresAt) { }

    private record ManualPayload(
            UUID tenantId,
            UUID clientStoreId,
            String city,
            UUID salespersonId,
            BigDecimal searchLongitude,
            BigDecimal searchLatitude,
            BigDecimal searchAccuracyMeters,
            Instant searchCapturedAt,
            String lookupStatus,
            Instant issuedAt,
            Instant expiresAt) { }
}
