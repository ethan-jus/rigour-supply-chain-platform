package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.sales.temporarycheckin.TemporaryCheckinStoreSelectionTokenService.Candidate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TemporaryCheckinStoreSelectionTokenServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SALESPERSON_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CLIENT_STORE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-28T02:00:00Z");
    private static final BigDecimal LONGITUDE = new BigDecimal("116.3971280");
    private static final BigDecimal LATITUDE = new BigDecimal("39.9165270");
    private static final BigDecimal ACCURACY_METERS = new BigDecimal("8.5");
    private static final Instant CAPTURED_AT = NOW.minusSeconds(30);

    @Test
    void bindsCandidateTenantCitySalespersonLocationAndExpiry() {
        TemporaryCheckinStoreSelectionTokenService issuer = serviceAt(TENANT_ID, NOW);
        Candidate candidate = candidate();
        String token = issuer.issue(SALESPERSON_ID, "北京",
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270"),
                new BigDecimal("8.5"), NOW.minusSeconds(30), candidate);

        assertThat(issuer.verify(token, SALESPERSON_ID, "北京",
                new BigDecimal("116.3975000"), new BigDecimal("39.9165270")))
                .isEqualTo(candidate);
        assertThat(serviceAt(TENANT_ID, NOW.plusSeconds(11 * 60)).verify(token, SALESPERSON_ID, "北京",
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270")))
                .isEqualTo(candidate);
        assertInvalid(() -> issuer.verify(token, SALESPERSON_ID, "深圳",
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270")));
        assertInvalid(() -> issuer.verify(token, UUID.randomUUID(), "北京",
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270")));
        assertInvalid(() -> issuer.verify(token, SALESPERSON_ID, "北京",
                new BigDecimal("116.4100000"), new BigDecimal("39.9165270")));
        assertInvalid(() -> serviceAt(TENANT_ID, NOW.plusSeconds(60 * 60 + 1)).verify(token, SALESPERSON_ID, "北京",
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270")));
        assertInvalid(() -> issuer.verify(token.substring(0, token.length() - 1) + "x",
                SALESPERSON_ID, "北京",
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270")));
    }

    @Test
    void manualTokenIsLimitedToManualPurposeAndEmptyOrUnavailableLookup() {
        TemporaryCheckinStoreSelectionTokenService service = serviceAt(TENANT_ID, NOW);
        String emptyToken = service.issueManual(CLIENT_STORE_ID, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT, "EMPTY");
        String unavailableToken = service.issueManual(CLIENT_STORE_ID, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT, "UNAVAILABLE");

        service.verifyManual(emptyToken, CLIENT_STORE_ID, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT);
        service.verifyManual(unavailableToken, CLIENT_STORE_ID, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT);
        assertThatThrownBy(() -> service.issueManual(CLIENT_STORE_ID, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT, "SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class);

        String candidateToken = service.issue(SALESPERSON_ID, "北京", LONGITUDE, LATITUDE,
                ACCURACY_METERS, CAPTURED_AT, candidate());
        assertInvalidManual(() -> service.verifyManual(candidateToken, CLIENT_STORE_ID,
                SALESPERSON_ID, "北京", LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        assertInvalid(() -> service.verify(emptyToken, SALESPERSON_ID, "北京", LONGITUDE, LATITUDE));
    }

    @Test
    void manualTokenBindsTenantStoreSalespersonCityAndExactLocationEvidence() {
        TemporaryCheckinStoreSelectionTokenService issuer = serviceAt(TENANT_ID, NOW);
        String token = issuer.issueManual(CLIENT_STORE_ID, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT, "EMPTY");

        issuer.verifyManual(token, CLIENT_STORE_ID, SALESPERSON_ID, "北京",
                new BigDecimal("116.39712800"), new BigDecimal("39.91652700"),
                new BigDecimal("8.50"), CAPTURED_AT);
        assertInvalidManual(() -> serviceAt(OTHER_TENANT_ID, NOW).verifyManual(token,
                CLIENT_STORE_ID, SALESPERSON_ID, "北京", LONGITUDE, LATITUDE,
                ACCURACY_METERS, CAPTURED_AT));
        assertInvalidManual(() -> issuer.verifyManual(token, UUID.randomUUID(), SALESPERSON_ID,
                "北京", LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        assertInvalidManual(() -> issuer.verifyManual(token, CLIENT_STORE_ID, UUID.randomUUID(),
                "北京", LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        assertInvalidManual(() -> issuer.verifyManual(token, CLIENT_STORE_ID, SALESPERSON_ID,
                "深圳", LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        assertInvalidManual(() -> issuer.verifyManual(token, CLIENT_STORE_ID, SALESPERSON_ID,
                "北京", LONGITUDE.add(new BigDecimal("0.0000001")), LATITUDE,
                ACCURACY_METERS, CAPTURED_AT));
        assertInvalidManual(() -> issuer.verifyManual(token, CLIENT_STORE_ID, SALESPERSON_ID,
                "北京", LONGITUDE, LATITUDE, ACCURACY_METERS.add(new BigDecimal("0.1")),
                CAPTURED_AT));
        assertInvalidManual(() -> issuer.verifyManual(token, CLIENT_STORE_ID, SALESPERSON_ID,
                "北京", LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT.minusSeconds(1)));
    }

    @Test
    void manualTokenRejectsTamperingAndExpiry() {
        TemporaryCheckinStoreSelectionTokenService issuer = serviceAt(TENANT_ID, NOW);
        String token = issuer.issueManual(CLIENT_STORE_ID, SALESPERSON_ID, "北京",
                LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT, "UNAVAILABLE");

        assertInvalidManual(() -> issuer.verifyManual(tamperSignature(token), CLIENT_STORE_ID,
                SALESPERSON_ID, "北京", LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT));
        serviceAt(TENANT_ID, NOW.plusSeconds(59 * 60)).verifyManual(token, CLIENT_STORE_ID,
                SALESPERSON_ID, "北京", LONGITUDE, LATITUDE, ACCURACY_METERS, CAPTURED_AT);
        assertInvalidManual(() -> serviceAt(TENANT_ID, NOW.plusSeconds(60 * 60)).verifyManual(
                token, CLIENT_STORE_ID, SALESPERSON_ID, "北京", LONGITUDE, LATITUDE,
                ACCURACY_METERS, CAPTURED_AT));
    }

    private static TemporaryCheckinStoreSelectionTokenService serviceAt(UUID tenantId, Instant instant) {
        TemporaryCheckinProperties properties = new TemporaryCheckinProperties();
        properties.setEnabled(true);
        properties.setTenantId(tenantId.toString());
        properties.setIdentityEnforcementEnabled(true);
        properties.setIdentitySigningKeyBase64(Base64.getEncoder().encodeToString(
                "temporary-checkin-selection-signing-key".getBytes(StandardCharsets.UTF_8)));
        return new TemporaryCheckinStoreSelectionTokenService(properties,
                JsonMapper.builder().findAndAddModules().build(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static Candidate candidate() {
        return new Candidate("B0FFTESTPOI", "高德候选门店", "北京市东城区测试路1号",
                new BigDecimal("116.403000"), new BigDecimal("39.912000"), "北京市", "110101");
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(TemporaryCheckinException.class,
                        exception -> assertThat(exception.getMessage()).contains("重新搜索选择"));
    }

    private static void assertInvalidManual(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(TemporaryCheckinException.class,
                        exception -> assertThat(exception.getMessage()).contains("重新搜索后再手工录入"));
    }

    private static String tamperSignature(String token) {
        char last = token.charAt(token.length() - 1);
        return token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');
    }
}
