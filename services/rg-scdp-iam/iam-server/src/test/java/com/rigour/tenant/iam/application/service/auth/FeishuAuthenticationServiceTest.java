package com.rigour.tenant.iam.application.service.auth;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.tenant.iam.application.port.out.AccessTokenIssuer;
import com.rigour.tenant.iam.application.port.out.FeishuIdentityProvider;
import com.rigour.tenant.iam.application.port.out.FeishuIdentityProviderException;
import com.rigour.tenant.iam.application.port.out.FeishuIdentityStore;
import com.rigour.tenant.iam.domain.model.session.AuthSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class FeishuAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final UUID SESSION_ID = UUID.fromString("01987654-3210-7000-8000-000000000001");
    private static final UUID EXTERNAL_ID = UUID.fromString("01987654-3210-7000-8000-000000000002");
    private static final UUID TENANT_ID = UUID.fromString("01987654-3210-7000-8000-000000000003");
    private static final UUID USER_ID = UUID.fromString("01987654-3210-7000-8000-000000000004");

    @Test
    void exchangesBoundFeishuIdentityAndCreatesPlatformSession() {
        AtomicReference<AuthSession> savedSession = new AtomicReference<>();
        FeishuIdentityStore.BoundIdentity bound = new FeishuIdentityStore.BoundIdentity(
                EXTERNAL_ID, TENANT_ID, USER_ID, "销售员甲", 3, 5, 7);
        FeishuIdentityStore store = new FeishuIdentityStore() {
            @Override
            public Optional<BoundIdentity> findActive(String tenantKey, String openId) {
                assertThat(tenantKey).isEqualTo("tenant-key");
                assertThat(openId).isEqualTo("open-id");
                return Optional.of(bound);
            }

            @Override
            public void completeLogin(BoundIdentity identity, AuthSession session, Instant verifiedAt) {
                assertThat(identity).isEqualTo(bound);
                assertThat(verifiedAt).isEqualTo(NOW);
                savedSession.set(session);
            }
        };
        AtomicReference<AccessTokenIssuer.TenantClaims> issuedClaims = new AtomicReference<>();
        AccessTokenIssuer tokenIssuer = claims -> {
            issuedClaims.set(claims);
            return "platform-access-token";
        };
        FeishuAuthenticationService service = service(
                code -> new FeishuIdentityProvider.VerifiedIdentity(
                        "tenant-key", "open-id", "飞书姓名", "https://example.test/avatar.png"),
                store, tokenIssuer);

        byte[] userAgentHash = new byte[32];
        byte[] ipAddress = new byte[] {127, 0, 0, 1};
        var result = service.login(new FeishuAuthenticationService.LoginCommand(
                "  one-time-code  ", "Feishu H5", userAgentHash, ipAddress));

        assertThat(result.token()).isEqualTo("platform-access-token");
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.tenantId()).isEqualTo(TENANT_ID);
        assertThat(result.displayName()).isEqualTo("销售员甲");
        assertThat(result.avatarUrl()).isEqualTo("https://example.test/avatar.png");
        assertThat(result.feishuOpenId()).isEqualTo("open-id");
        assertThat(savedSession.get().id()).isEqualTo(SESSION_ID);
        assertThat(savedSession.get().clientType()).isEqualTo(AuthSession.ClientType.FEISHU_H5);
        assertThat(savedSession.get().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
        assertThat(issuedClaims.get()).isEqualTo(new AccessTokenIssuer.TenantClaims(
                SESSION_ID, TENANT_ID, USER_ID, 0, 5, 7, NOW, NOW.plus(Duration.ofHours(1))));
    }

    @Test
    void rejectsVerifiedButUnboundFeishuIdentity() {
        FeishuIdentityStore store = new FeishuIdentityStore() {
            @Override
            public Optional<BoundIdentity> findActive(String tenantKey, String openId) {
                return Optional.empty();
            }

            @Override
            public void completeLogin(BoundIdentity identity, AuthSession session, Instant verifiedAt) {
                throw new AssertionError("unbound identity must not create a session");
            }
        };
        FeishuAuthenticationService service = service(
                code -> new FeishuIdentityProvider.VerifiedIdentity(
                        "tenant-key", "open-id", null, null), store,
                claims -> { throw new AssertionError("unbound identity must not receive a token"); });

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.login(new FeishuAuthenticationService.LoginCommand(
                        "one-time-code", null, null, null)))
                .satisfies(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.FEISHU_IDENTITY_NOT_BOUND));
    }

    @Test
    void mapsRejectedFeishuCodeToStableBusinessError() {
        FeishuAuthenticationService service = service(code -> {
            throw new FeishuIdentityProviderException(
                    FeishuIdentityProviderException.Reason.INVALID_CODE, 20003, 200);
        }, new NeverUsedStore(), claims -> "unused");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.login(new FeishuAuthenticationService.LoginCommand(
                        "expired-code", null, null, null)))
                .satisfies(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.FEISHU_AUTH_CODE_INVALID));
    }

    private static FeishuAuthenticationService service(
            FeishuIdentityProvider provider, FeishuIdentityStore store, AccessTokenIssuer tokenIssuer) {
        return new FeishuAuthenticationService(
                provider, store, tokenIssuer, () -> SESSION_ID,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(8), Duration.ofHours(1));
    }

    private static final class NeverUsedStore implements FeishuIdentityStore {
        @Override
        public Optional<BoundIdentity> findActive(String tenantKey, String openId) {
            throw new AssertionError("rejected code must not query identity mapping");
        }

        @Override
        public void completeLogin(BoundIdentity identity, AuthSession session, Instant verifiedAt) {
            throw new AssertionError("rejected code must not create a session");
        }
    }
}
