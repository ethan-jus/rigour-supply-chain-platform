package com.rigour.tenant.iam.application.service.auth;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.tenant.iam.application.port.out.AccessTokenIssuer;
import com.rigour.tenant.iam.application.port.out.FeishuIdentityProvider;
import com.rigour.tenant.iam.application.port.out.FeishuIdentityProviderException;
import com.rigour.tenant.iam.application.port.out.FeishuIdentityStore;
import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.domain.model.session.AuthSession;
import com.rigour.tenant.iam.domain.model.session.AuthSession.ClientType;
import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 飞书H5免登：验证一次性code、解析已绑定IAM用户、创建会话并签发平台令牌。 */
public final class FeishuAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(FeishuAuthenticationService.class);

    private final FeishuIdentityProvider identityProvider;
    private final FeishuIdentityStore identityStore;
    private final AccessTokenIssuer accessTokenIssuer;
    private final IdentifierGenerator identifierGenerator;
    private final Clock clock;
    private final Duration sessionTimeToLive;
    private final Duration accessTokenTimeToLive;

    public FeishuAuthenticationService(
            FeishuIdentityProvider identityProvider,
            FeishuIdentityStore identityStore,
            AccessTokenIssuer accessTokenIssuer,
            IdentifierGenerator identifierGenerator,
            Clock clock,
            Duration sessionTimeToLive,
            Duration accessTokenTimeToLive
    ) {
        this.identityProvider = identityProvider;
        this.identityStore = identityStore;
        this.accessTokenIssuer = accessTokenIssuer;
        this.identifierGenerator = identifierGenerator;
        this.clock = clock;
        this.sessionTimeToLive = sessionTimeToLive;
        this.accessTokenTimeToLive = accessTokenTimeToLive;
    }

    public LoginResult login(LoginCommand command) {
        String code = normalizedCode(command.code());
        FeishuIdentityProvider.VerifiedIdentity verified;
        try {
            verified = identityProvider.exchange(code);
        } catch (FeishuIdentityProviderException exception) {
            throw new BusinessException(switch (exception.reason()) {
                case INVALID_CODE -> ErrorCode.FEISHU_AUTH_CODE_INVALID;
                case CONFIG_INVALID -> ErrorCode.FEISHU_AUTH_CONFIG_INVALID;
                case UPSTREAM_FAILED -> ErrorCode.FEISHU_AUTH_UPSTREAM_FAILED;
            });
        }

        FeishuIdentityStore.BoundIdentity bound = identityStore
                .findActive(verified.tenantKey(), verified.openId())
                .orElseThrow(() -> {
                    // 绑定排障需要定位外部身份；openId/tenantKey 不是令牌或密钥，且成功响应本就会回传 openId。
                    log.warn("飞书身份未绑定平台用户 externalTenantKey={} externalUserId={}",
                            verified.tenantKey(), verified.openId());
                    return new BusinessException(ErrorCode.FEISHU_IDENTITY_NOT_BOUND);
                });
        Instant now = clock.instant();
        AuthSession session = new AuthSession(
                identifierGenerator.nextId(), PrincipalScope.TENANT, bound.tenantId(), bound.userId(),
                ClientType.FEISHU_H5, command.deviceName(), null,
                command.userAgentHash(), command.ipAddress(), now, now, now.plus(sessionTimeToLive));
        identityStore.completeLogin(bound, session, now);
        String token = accessTokenIssuer.issue(new AccessTokenIssuer.TenantClaims(
                session.id(), bound.tenantId(), bound.userId(), 0,
                bound.userSecurityVersion(), bound.tenantPolicyVersion(),
                now, now.plus(accessTokenTimeToLive)));
        return new LoginResult(token, bound.userId(), bound.tenantId(), bound.displayName(),
                verified.avatarUrl(), verified.openId());
    }

    private static String normalizedCode(String value) {
        if (value == null) throw new BusinessException(ErrorCode.FEISHU_AUTH_CODE_INVALID);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 2048) {
            throw new BusinessException(ErrorCode.FEISHU_AUTH_CODE_INVALID);
        }
        return normalized;
    }

    public record LoginCommand(String code, String deviceName, byte[] userAgentHash, byte[] ipAddress) {
        public LoginCommand {
            deviceName = normalized(deviceName);
            userAgentHash = copy(userAgentHash);
            ipAddress = copy(ipAddress);
            if (deviceName != null && deviceName.length() > 128) {
                throw new IllegalArgumentException("deviceName is too long");
            }
        }

        @Override public byte[] userAgentHash() { return copy(userAgentHash); }
        @Override public byte[] ipAddress() { return copy(ipAddress); }

        private static String normalized(String value) {
            if (value == null) return null;
            String normalized = value.strip();
            return normalized.isEmpty() ? null : normalized;
        }

        private static byte[] copy(byte[] value) {
            return value == null ? null : Arrays.copyOf(value, value.length);
        }
    }

    public record LoginResult(
            String token, java.util.UUID userId, java.util.UUID tenantId,
            String displayName, String avatarUrl, String feishuOpenId
    ) { }
}
