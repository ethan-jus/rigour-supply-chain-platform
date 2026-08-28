package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder.GeocodeResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TemporaryCheckinLocationVerificationTokenServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SALESPERSON_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-28T02:00:00Z");
    private static final BigDecimal LONGITUDE = new BigDecimal("116.3971280");
    private static final BigDecimal LATITUDE = new BigDecimal("39.9165270");
    private static final BigDecimal ACCURACY_METERS = new BigDecimal("8.5");
    private static final Instant CAPTURED_AT = NOW.minusSeconds(30);

    @Test
    void returnsSignedGeocodeWhenEveryLocationBindingMatches() {
        TemporaryCheckinLocationVerificationTokenService service = serviceAt(TENANT_ID, NOW);
        GeocodeResult geocode = resolvedGeocode();
        String token = service.issue(SALESPERSON_ID, "北京", LONGITUDE, LATITUDE,
                ACCURACY_METERS, CAPTURED_AT, geocode);

        assertThat(service.verify(token, SALESPERSON_ID, "北京",
                new BigDecimal("116.39712800"), new BigDecimal("39.91652700"),
                new BigDecimal("8.50"), CAPTURED_AT)).isEqualTo(geocode);
    }

    @Test
    void rejectsDifferentTenantSalespersonCityOrLocationEvidence() {
        TemporaryCheckinLocationVerificationTokenService issuer = serviceAt(TENANT_ID, NOW);
        String token = issuer.issue(SALESPERSON_ID, "北京", LONGITUDE, LATITUDE,
                ACCURACY_METERS, CAPTURED_AT, resolvedGeocode());

        assertInvalid(() -> serviceAt(OTHER_TENANT_ID, NOW).verify(token, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        assertInvalid(() -> issuer.verify(token, UUID.randomUUID(), "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        assertInvalid(() -> issuer.verify(token, SALESPERSON_ID, "深圳",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        assertInvalid(() -> issuer.verify(token, SALESPERSON_ID, "北京",
                LONGITUDE.add(new BigDecimal("0.0000001")), LATITUDE,
                ACCURACY_METERS, CAPTURED_AT));
        assertInvalid(() -> issuer.verify(token, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS.add(new BigDecimal("0.1")), CAPTURED_AT));
        assertInvalid(() -> issuer.verify(token, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT.minusSeconds(1)));
    }

    @Test
    void rejectsTamperedOrExpiredLocationProof() {
        TemporaryCheckinLocationVerificationTokenService issuer = serviceAt(TENANT_ID, NOW);
        String token = issuer.issue(SALESPERSON_ID, "北京", LONGITUDE, LATITUDE,
                ACCURACY_METERS, CAPTURED_AT, resolvedGeocode());

        assertInvalid(() -> issuer.verify(tamperSignature(token), SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        assertThat(serviceAt(TENANT_ID, NOW.plusSeconds(59 * 60)).verify(
                token, SALESPERSON_ID, "北京", LONGITUDE, LATITUDE,
                ACCURACY_METERS, CAPTURED_AT)).isEqualTo(resolvedGeocode());
        assertInvalid(() -> serviceAt(TENANT_ID, CAPTURED_AT.plusSeconds(60 * 60)).verify(
                token, SALESPERSON_ID, "北京", LONGITUDE, LATITUDE,
                ACCURACY_METERS, CAPTURED_AT));
    }

    private static TemporaryCheckinLocationVerificationTokenService serviceAt(
            UUID tenantId, Instant instant) {
        TemporaryCheckinProperties properties = new TemporaryCheckinProperties();
        properties.setEnabled(true);
        properties.setTenantId(tenantId.toString());
        properties.setIdentityEnforcementEnabled(true);
        properties.setIdentitySigningKeyBase64(signingKey());
        return new TemporaryCheckinLocationVerificationTokenService(properties,
                JsonMapper.builder().findAndAddModules().build(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static GeocodeResult resolvedGeocode() {
        return new GeocodeResult("RESOLVED", "测试路1号", "北京市东城区测试路1号", "110101",
                "北京市", "北京市", "东城区", "测试街道",
                new BigDecimal("116.403370"), new BigDecimal("39.917010"), null);
    }

    private static String signingKey() {
        return Base64.getEncoder().encodeToString(
                "temporary-checkin-location-signing-key".getBytes(StandardCharsets.UTF_8));
    }

    private static String tamperSignature(String token) {
        char last = token.charAt(token.length() - 1);
        return token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(TemporaryCheckinException.class,
                        exception -> assertThat(exception.getMessage()).contains("重新获取当前位置"));
    }
}
