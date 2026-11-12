package com.rigour.integration.application.service.thirdparty.oauth;

import static com.rigour.integration.application.service.thirdparty.oauth.OAuthSupport.*;

import com.rigour.integration.application.port.out.thirdparty.oauth.*;
import com.rigour.integration.application.port.out.thirdparty.oauth.ThirdPartyTokenStore.Stored;
import com.rigour.integration.domain.thirdparty.oauth.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** 刷新仅持有短期数据库租约，网络调用不占用数据库事务。 */
@Service
public class ThirdPartyTokenService {
    private final ThirdPartyProviderRegistry providers;
    private final ThirdPartyTokenStore tokens;
    private final ThirdPartyAuthorizationSessionStore sessions;
    private final TransactionTemplate tx;

    public ThirdPartyTokenService(
            ThirdPartyProviderRegistry providers,
            ThirdPartyTokenStore tokens,
            ThirdPartyAuthorizationSessionStore sessions,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.providers = providers;
        this.tokens = tokens;
        this.sessions = sessions;
        this.tx = new TransactionTemplate(manager);
    }

    public Optional<Stored> current(UUID tenant, String platform, UUID connector) {
        return tokens.find(tenant, platform, connector);
    }

    public OAuthToken valid(UUID tenant, String platform, UUID connector) {
        var provider = providers.require(platform);
        var c = provider.configuration(tenant, connector);
        Instant now = Instant.now();
        Stored before =
                tokens.find(tenant, platform, connector)
                        .orElseThrow(() -> new IllegalStateException("请先完成平台授权"));
        if (!"AUTHORIZED".equals(before.authStatus())
                || !Objects.equals(before.appKey(), c.appKey()))
            throw new IllegalStateException("平台需要重新授权");
        if (before.token().expiresAt() != null
                && before.token().expiresAt().isAfter(now.plusSeconds(30))
                && before.nextRefreshAt() != null
                && before.nextRefreshAt().isAfter(now)) return before.token();
        UUID lease = UUID.randomUUID();
        Stored claimed =
                tx.execute(
                        status -> {
                            var row = tokens.lock(before.id());
                            if (!"AUTHORIZED".equals(row.authStatus())
                                    || !Objects.equals(row.appKey(), c.appKey()))
                                throw new IllegalStateException("平台需要重新授权");
                            if (row.revision() != before.revision()
                                    || leased(row.refreshLeaseExpiresAt(), now)
                                    || sessions.active(row.id(), now)) return null;
                            return tokens.claimRefresh(row, lease, now.plusSeconds(90))
                                    ? tokens.lock(row.id())
                                    : null;
                        });
        if (claimed == null) {
            var latest = tokens.find(tenant, platform, connector).orElseThrow();
            if ("AUTHORIZED".equals(latest.authStatus())
                    && Objects.equals(latest.appKey(), c.appKey())
                    && latest.token().expiresAt() != null
                    && latest.token().expiresAt().isAfter(Instant.now().plusSeconds(30)))
                return latest.token();
            throw new IllegalStateException("平台授权正在更新，请稍后重试");
        }
        try {
            if (claimed.token().refreshToken() == null || claimed.token().refreshToken().isBlank())
                throw new OAuthFailure(
                        OAuthFailure.Kind.REAUTHORIZE, "NO_REFRESH_TOKEN", "平台需要重新授权");
            if (claimed.token().refreshExpiresAt() != null
                    && !claimed.token().refreshExpiresAt().isAfter(now))
                throw new OAuthFailure(
                        OAuthFailure.Kind.REAUTHORIZE, "REFRESH_EXPIRED", "平台需要重新授权");
            var refreshed = provider.refresh(c, claimed.token());
            Instant received = Instant.now();
            Instant next = nextRefresh(refreshed, received);
            // 防止配置变更及较旧的刷新响应覆盖新授权。
            var current = provider.configuration(tenant, connector);
            bound(current, c.appKey(), c.fingerprint());
            return tx.execute(
                    status ->
                            tokens.activate(
                                            claimed,
                                            c.appKey(),
                                            refreshed,
                                            received,
                                            next,
                                            null,
                                            false)
                                    .token());
        } catch (RuntimeException failure) {
            boolean temporary =
                    failure instanceof OAuthFailure f && f.kind() == OAuthFailure.Kind.TEMPORARY;
            String safe = temporary ? "平台令牌刷新暂未成功，系统将重试" : "平台令牌刷新未完成，需要重新授权";
            tx.executeWithoutResult(
                    status ->
                            tokens.refreshFailed(
                                    claimed,
                                    lease,
                                    temporary ? "AUTHORIZED" : "REAUTHORIZE_REQUIRED",
                                    temporary ? "REFRESH_TEMPORARY" : "REFRESH_REAUTHORIZE",
                                    safe,
                                    Instant.now()
                                            .plusSeconds(
                                                    Math.min(
                                                            300,
                                                            30L * (claimed.failureCount() + 1)))));
            if (temporary && claimed.token().expiresAt().isAfter(Instant.now().plusSeconds(30)))
                return claimed.token();
            throw new IllegalStateException(safe);
        }
    }

    @Scheduled(fixedDelayString = "${rigour.thirdparty.oauth.refresh-poll-ms:30000}")
    public void refreshDue() {
        for (var row : tokens.due(Instant.now(), 50))
            try {
                valid(row.tenantId(), row.platformCode(), row.connectorId());
            } catch (RuntimeException ignored) {
                /* 脱敏状态和下一次重试时间在Token记录中维护。 */
            }
    }
}
