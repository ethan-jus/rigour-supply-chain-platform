package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.IdentityVerifyRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.RiskHistory;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.SalespersonRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.AuthorizedRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.IdentityVerification;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.RequestRiskFacts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class TemporaryCheckinSalesIdentityServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SALESPERSON_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_SALESPERSON_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String PERSONAL_CODE = "A7KM29QX";
    private static final String PROXY_MARKER = "test-only-trusted-proxy-marker-32-bytes";
    private static final Instant NOW = Instant.parse("2026-08-25T02:00:00Z");

    private TemporaryCheckinRepository repository;
    private TemporaryCheckinSalesIdentityService service;

    @BeforeEach
    void setUp() {
        repository = mock(TemporaryCheckinRepository.class);
        TemporaryCheckinProperties properties = new TemporaryCheckinProperties();
        properties.setEnabled(true);
        properties.setTenantId(TENANT_ID.toString());
        properties.setIdentityEnforcementEnabled(true);
        properties.setIdentitySigningKeyBase64(base64Key("identity-cookie-signing-key"));
        properties.setRiskHmacKeyBase64(base64Key("risk-data-hmac-key"));
        properties.setTrustedProxyMarker(PROXY_MARKER);
        properties.setCredentialPbkdf2Iterations(100_000);
        service = new TemporaryCheckinSalesIdentityService(
                repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void verifiesPersonalCodeAndBindsSecureCookiesToTheSelectedSalesperson() {
        SalespersonRow salesperson = salesperson(service.encodePersonalCode(PERSONAL_CODE), 1);
        when(repository.findSalesperson(TENANT_ID, SALESPERSON_ID)).thenReturn(Optional.of(salesperson));

        IdentityVerification verification = service.verify(
                new IdentityVerifyRequest(SALESPERSON_ID, "北京", PERSONAL_CODE), requestFacts(null, null));

        assertThat(verification.view().authenticated()).isTrue();
        assertThat(verification.view().salespersonId()).isEqualTo(SALESPERSON_ID);
        assertThat(verification.view().salespersonName()).isEqualTo("张三");
        assertThat(verification.deviceCookie().toString())
                .contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/");
        assertThat(verification.identityCookie().toString())
                .contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/");

        TemporaryCheckinRequestFacts authenticatedFacts = requestFacts(
                verification.deviceCookie().getValue(), verification.identityCookie().getValue());
        AuthorizedRequest authorized = service.requireSalesperson(SALESPERSON_ID, authenticatedFacts);
        assertThat(authorized.salesperson().id()).isEqualTo(SALESPERSON_ID);
        assertThat(authorized.identityMethod()).isEqualTo("PERSONAL_CODE");

        assertThatThrownBy(() -> service.requireSalesperson(OTHER_SALESPERSON_ID, authenticatedFacts))
                .isInstanceOfSatisfying(TemporaryCheckinException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.code()).isEqualTo("TEMP_CHECKIN_IDENTITY_INVALID");
                });
    }

    @Test
    void rejectsUntrustedProxyAndDoesNotRevealWhetherThePersonalCodeWasWrong() {
        SalespersonRow salesperson = salesperson(service.encodePersonalCode(PERSONAL_CODE), 1);
        when(repository.findSalesperson(TENANT_ID, SALESPERSON_ID)).thenReturn(Optional.of(salesperson));

        TemporaryCheckinRequestFacts untrusted = new TemporaryCheckinRequestFacts(
                "203.0.113.28", "browser-controlled-marker", "Mobile Safari", null, null);
        assertThatThrownBy(() -> service.verify(
                new IdentityVerifyRequest(SALESPERSON_ID, "北京", PERSONAL_CODE), untrusted))
                .isInstanceOfSatisfying(TemporaryCheckinException.class,
                        exception -> assertThat(exception.getMessage()).isEqualTo("请求未经受信任代理"));

        assertThatThrownBy(() -> service.verify(
                new IdentityVerifyRequest(SALESPERSON_ID, "北京", "WRONG234"), requestFacts(null, null)))
                .isInstanceOfSatisfying(TemporaryCheckinException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("销售身份验证失败");
                    assertThat(exception.getMessage()).doesNotContain("WRONG234");
                });
    }

    @Test
    void reportsMissingCredentialAsAnAdministratorProvisioningRequirement() {
        when(repository.findSalesperson(TENANT_ID, SALESPERSON_ID))
                .thenReturn(Optional.of(salesperson(null, 1)));

        assertThatThrownBy(() -> service.verify(
                new IdentityVerifyRequest(SALESPERSON_ID, "北京", PERSONAL_CODE), requestFacts(null, null)))
                .isInstanceOfSatisfying(TemporaryCheckinException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).contains("尚未设置个人打卡码", "联系城市管理员");
                });
    }

    @Test
    void issuingTemporaryCodeStoresOnlyPbkdf2DigestAndIncrementsCredentialVersion() {
        when(repository.findSalesperson(TENANT_ID, SALESPERSON_ID))
                .thenReturn(Optional.of(salesperson(null, 1)));
        when(repository.rotateSalespersonCredential(
                eq(TENANT_ID), eq(SALESPERSON_ID), any(), eq("city-beijing"), eq("新员工首次开通"), eq(NOW)))
                .thenReturn(2);

        var issued = service.issueTemporaryCode(
                TENANT_ID, SALESPERSON_ID, "city-beijing", "新员工首次开通");

        assertThat(issued.temporaryCode()).matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{10}");
        assertThat(issued.credentialVersion()).isEqualTo(2);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).rotateSalespersonCredential(
                eq(TENANT_ID), eq(SALESPERSON_ID), hash.capture(),
                eq("city-beijing"), eq("新员工首次开通"), eq(NOW));
        assertThat(hash.getValue())
                .startsWith("pbkdf2-sha256$100000$")
                .doesNotContain(issued.temporaryCode());
    }

    @Test
    void credentialVersionChangeRevokesPreviouslyIssuedIdentityCookie() {
        String hash = service.encodePersonalCode(PERSONAL_CODE);
        when(repository.findSalesperson(TENANT_ID, SALESPERSON_ID))
                .thenReturn(Optional.of(salesperson(hash, 1)));
        IdentityVerification verification = service.verify(
                new IdentityVerifyRequest(SALESPERSON_ID, "北京", PERSONAL_CODE), requestFacts(null, null));

        when(repository.findSalesperson(TENANT_ID, SALESPERSON_ID))
                .thenReturn(Optional.of(salesperson(hash, 2)));
        TemporaryCheckinRequestFacts authenticatedFacts = requestFacts(
                verification.deviceCookie().getValue(), verification.identityCookie().getValue());

        assertThatThrownBy(() -> service.current(authenticatedFacts))
                .isInstanceOfSatisfying(TemporaryCheckinException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.getMessage()).contains("个人打卡码已更新");
                });
    }

    @Test
    void calculatesDeviceSalespersonAndIpChurnAsRiskFlagsWithoutBlocking() {
        RequestRiskFacts facts = new RequestRiskFacts(
                "a".repeat(64), "b".repeat(64), "203.0.113.*", "c".repeat(64),
                "d".repeat(64), "Mobile Safari");
        AuthorizedRequest authorized = new AuthorizedRequest(
                salesperson("digest-not-used", 1), "PERSONAL_CODE", NOW, NOW.plusSeconds(3600),
                facts.deviceTokenHash(), facts);
        when(repository.findRiskHistory(
                TENANT_ID, SALESPERSON_ID, facts.deviceTokenHash(),
                facts.ipHash(), facts.ipNetworkHash(), NOW))
                .thenReturn(new RiskHistory(1, false, 2, false, 3, false, 2, false));

        var snapshot = service.evaluateRisk(authorized);

        assertThat(snapshot.level()).isEqualTo("HIGH");
        assertThat(snapshot.flags()).containsExactly(
                "DEVICE_MULTIPLE_SALES",
                "SALESPERSON_MULTIPLE_DEVICES",
                "SALESPERSON_IP_CHURN",
                "SHARED_IP_MULTIPLE_SALES");
        assertThat(snapshot.requestFacts().ipMasked()).isEqualTo("203.0.113.*");
    }

    private static TemporaryCheckinRequestFacts requestFacts(String deviceCookie, String identityCookie) {
        return new TemporaryCheckinRequestFacts(
                "203.0.113.28", PROXY_MARKER, "Mobile Safari Test", deviceCookie, identityCookie);
    }

    private static SalespersonRow salesperson(String secretHash, int credentialVersion) {
        return new SalespersonRow(
                SALESPERSON_ID, "feishu-sales-1", "张三", "北京", "销售",
                "在职", "ACTIVE", 1, secretHash, credentialVersion,
                null, null, null);
    }

    private static String base64Key(String seed) {
        byte[] value = new byte[32];
        byte[] source = seed.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < value.length; index++) value[index] = source[index % source.length];
        return Base64.getEncoder().encodeToString(value);
    }
}
