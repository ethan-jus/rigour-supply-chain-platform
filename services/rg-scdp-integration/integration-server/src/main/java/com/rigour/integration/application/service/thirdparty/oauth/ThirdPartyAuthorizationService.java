package com.rigour.integration.application.service.thirdparty.oauth;

import static com.rigour.integration.application.service.thirdparty.oauth.OAuthSupport.*;

import com.rigour.integration.application.port.out.thirdparty.oauth.*;
import com.rigour.integration.application.port.out.thirdparty.oauth.ThirdPartyAuthorizationSessionStore.Session;
import com.rigour.integration.domain.thirdparty.oauth.*;
import com.rigour.shared.context.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.*;
import java.util.*;

/** 公开回调只接受已登录用户发起且绑定当前浏览器的短期会话。 */
@Service
public class ThirdPartyAuthorizationService {
    public record Start(UUID id, String url, Instant expiresAt) {
        @Override
        public String toString() {
            return "OAuthStart[id=" + id + "]";
        }
    }

    public record Status(UUID id, String status, Instant expiresAt, String message) {}

    public record Redirect(URI uri, String cookieValue, boolean secure) {
        @Override
        public String toString() {
            return "OAuthRedirect[REDACTED]";
        }
    }

    private final ThirdPartyProviderRegistry providers;
    private final ThirdPartyTokenStore tokens;
    private final ThirdPartyAuthorizationSessionStore sessions;
    private final TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Value(
            "${rigour.thirdparty.oauth.session-retention-days:30}")
    private int retentionDays = 30;

    public ThirdPartyAuthorizationService(
            ThirdPartyProviderRegistry providers,
            ThirdPartyTokenStore tokens,
            ThirdPartyAuthorizationSessionStore sessions,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.providers = providers;
        this.tokens = tokens;
        this.sessions = sessions;
        this.tx = new TransactionTemplate(manager);
    }

    private CallerIdentity actor(String platform, String action) {
        AuthorizationContext.requirePermission(
                providers.require(platform).permissionPrefix() + ":" + action);
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null || !"TENANT".equals(actor.principalScope()))
            throw new AuthorizationDeniedException("tenant-user-caller");
        return actor;
    }

    public Start create(String platform, UUID connector) {
        var actor = actor(platform, "write");
        var c = providers.require(platform).configuration(actor.tenantId(), connector);
        String ticket = nonce();
        Instant now = Instant.now();
        Session s =
                tx.execute(
                        status -> {
                            var initial = tokens.ensure(c, actor.principalId());
                            var row = tokens.lock(initial.id());
                            if (leased(row.refreshLeaseExpiresAt(), now)
                                    || sessions.processing(row.id(), now))
                                throw new IllegalStateException("授权正在处理中，请稍后重试");
                            sessions.supersede(row.id(), now);
                            var added =
                                    new Session(
                                            UUID.randomUUID(),
                                            c.tenantId(),
                                            platform,
                                            connector,
                                            row.id(),
                                            actor.principalId(),
                                            c.appKey(),
                                            c.fingerprint(),
                                            hash(ticket),
                                            null,
                                            null,
                                            "CREATED",
                                            now.plusSeconds(600),
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            1);
                            sessions.insert(added);
                            return added;
                        });
        return new Start(
                s.id(),
                c.redirectUri().resolve("start?ticket=" + ticket).toString(),
                s.expiresAt());
    }

    public Status status(String platform, UUID id) {
        var a = actor(platform, "write");
        var s = sessions.find(id).orElseThrow(() -> new IllegalArgumentException("授权会话不存在"));
        if (!s.tenantId().equals(a.tenantId())
                || !s.actorId().equals(a.principalId())
                || !s.platformCode().equals(platform))
            throw new AuthorizationDeniedException("oauth-session-owner");
        return result(s);
    }

    public void cancel(String platform, UUID id) {
        status(platform, id);
        var s = sessions.find(id).orElseThrow();
        tx.executeWithoutResult(
                status -> {
                    tokens.lock(s.tokenId());
                    var row = sessions.lock(id);
                    if (leased(row.leaseUntil(), Instant.now()))
                        throw new IllegalStateException("授权结果正在确认，请稍后查看");
                    if (active(row)) terminal(row, "FAILED", "CANCELLED", "本次授权已取消");
                });
    }

    public Redirect open(String platform, String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("授权链接无效，请重新发起授权");
        var found =
                sessions.byHash(hash(ticket))
                        .orElseThrow(() -> new IllegalArgumentException("授权链接无效，请重新发起授权"));
        String binding = nonce();
        var row =
                tx.execute(
                        status -> {
                            tokens.lock(found.tokenId());
                            var s = sessions.lock(found.id());
                            check(s, platform);
                            if (!"CREATED".equals(s.status()))
                                throw new IllegalStateException("授权链接已使用，请从系统重新发起授权");
                            return sessions.update(
                                    s,
                                    s.progress(
                                            "REDIRECTED",
                                            hash(binding),
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null));
                        });
        var c = config(row);
        return new Redirect(
                providers.require(platform).authorizationUri(c),
                row.id() + "." + binding,
                "https".equals(c.redirectUri().getScheme()));
    }

    public Status callback(String platform, String cookie, String code) {
        if (cookie == null
                || cookie.length() > 100
                || code == null
                || code.isBlank()
                || code.length() > 2000) throw new IllegalArgumentException("缺少有效授权会话，请从本系统重新授权");
        String[] parts = cookie.split("\\.", -1);
        if (parts.length != 2 || !parts[1].matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("授权会话无效");
        UUID id;
        try {
            id = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("授权会话无效");
        }
        var found = sessions.find(id).orElseThrow(() -> new IllegalArgumentException("授权会话无效"));
        if (!sameHash(found.browserBindingHash(), parts[1])
                || !platform.equals(found.platformCode()))
            throw new IllegalArgumentException("授权会话不属于当前浏览器");
        var claimed = claim(found, code);
        if (!"EXCHANGING".equals(claimed.status()) || claimed.candidate() != null)
            return result(claimed);
        try {
            var provider = providers.require(platform);
            var c = config(claimed);
            var candidate = provider.exchange(c, code);
            nextRefresh(candidate, Instant.now());
            var saved =
                    tx.execute(
                            status -> {
                                tokens.lock(claimed.tokenId());
                                var row = sessions.lock(id);
                                lease(row, claimed);
                                return sessions.update(
                                        row,
                                        row.progress(
                                                "PENDING_VERIFICATION",
                                                row.browserBindingHash(),
                                                row.codeHash(),
                                                row.leaseId(),
                                                Instant.now().plusSeconds(90),
                                                row.exchangeAttemptedAt(),
                                                candidate,
                                                null,
                                                null,
                                                null));
                            });
            return verify(saved);
        } catch (RuntimeException failure) {
            return failClaim(claimed, failure, true);
        }
    }

    private Session claim(Session found, String code) {
        return tx.execute(
                status -> {
                    tokens.lock(found.tokenId());
                    var s = sessions.lock(found.id());
                    check(s, found.platformCode());
                    if (!active(s) || "PENDING_VERIFICATION".equals(s.status())) return s;
                    if ("EXCHANGING".equals(s.status())) {
                        if (leased(s.leaseUntil(), Instant.now()))
                            throw new IllegalStateException("授权结果正在处理中，请稍后查看");
                        return terminal(s, "UNCERTAIN", "EXCHANGE_UNCERTAIN", "上次授权结果未能确认，请重新授权");
                    }
                    if (!"REDIRECTED".equals(s.status()))
                        throw new IllegalStateException("本次授权已结束，请重新发起授权");
                    return sessions.update(
                            s,
                            s.progress(
                                    "EXCHANGING",
                                    s.browserBindingHash(),
                                    hash(code),
                                    UUID.randomUUID(),
                                    Instant.now().plusSeconds(90),
                                    Instant.now(),
                                    null,
                                    null,
                                    null,
                                    null));
                });
    }

    private Status verify(Session claimed) {
        try {
            var c = config(claimed);
            boolean proof =
                    providers.require(claimed.platformCode()).verifySubject(c, claimed.candidate());
            return tx.execute(
                    status -> {
                        var token = tokens.lock(claimed.tokenId());
                        var row = sessions.lock(claimed.id());
                        lease(row, claimed);
                        check(row, claimed.platformCode());
                        if (!proof)
                            return result(
                                    sessions.update(
                                            row,
                                            row.progress(
                                                    "PENDING_VERIFICATION",
                                                    row.browserBindingHash(),
                                                    row.codeHash(),
                                                    null,
                                                    null,
                                                    row.exchangeAttemptedAt(),
                                                    row.candidate(),
                                                    null,
                                                    "SUBJECT_PENDING",
                                                    "暂未取得可核验的企业订单，系统将继续核验")));
                        tokens.activate(
                                token,
                                c.appKey(),
                                row.candidate(),
                                Instant.now(),
                                nextRefresh(row.candidate(), Instant.now()),
                                row.actorId(),
                                true);
                        return result(terminal(row, "SUCCEEDED", null, null));
                    });
        } catch (RuntimeException e) {
            return failClaim(claimed, e, false);
        }
    }

    private Status failClaim(Session claimed, RuntimeException failure, boolean exchange) {
        boolean temporary =
                !exchange
                        && (!(failure instanceof OAuthFailure f)
                                || f.kind() == OAuthFailure.Kind.TEMPORARY);
        boolean rejected =
                failure instanceof OAuthFailure f && f.kind() == OAuthFailure.Kind.REAUTHORIZE;
        String state =
                temporary ? "PENDING_VERIFICATION" : exchange && !rejected ? "UNCERTAIN" : "FAILED";
        String errorCode =
                failure instanceof OAuthFailure f
                                && f.code() != null
                                && f.code().matches("[A-Za-z0-9_-]{1,100}")
                        ? f.code()
                        : exchange ? "EXCHANGE_UNCERTAIN" : "SUBJECT_MISMATCH";
        String message =
                temporary
                        ? "企业授权核验暂未完成，系统将重试"
                        : exchange
                                ? rejected ? "货拉拉令牌响应未通过校验，请联系管理员核对后重新授权" : "授权交换结果未能确认，请重新发起授权"
                                : "授权主体核验失败，请核对企业账号后重新授权";
        return tx.execute(
                status -> {
                    tokens.lock(claimed.tokenId());
                    var row = sessions.lock(claimed.id());
                    if (!Objects.equals(row.leaseId(), claimed.leaseId()) || !active(row))
                        return result(row);
                    return result(
                            sessions.update(
                                    row,
                                    row.progress(
                                            state,
                                            row.browserBindingHash(),
                                            row.codeHash(),
                                            null,
                                            null,
                                            row.exchangeAttemptedAt(),
                                            temporary ? row.candidate() : null,
                                            temporary ? null : Instant.now(),
                                            temporary ? "VERIFY_RETRY" : errorCode,
                                            message)));
                });
    }

    private Session terminal(Session s, String status, String code, String message) {
        return sessions.update(
                s,
                s.progress(
                        status,
                        s.browserBindingHash(),
                        s.codeHash(),
                        null,
                        null,
                        s.exchangeAttemptedAt(),
                        null,
                        Instant.now(),
                        code,
                        message));
    }

    private OAuthConfiguration config(Session s) {
        var c = providers.require(s.platformCode()).configuration(s.tenantId(), s.connectorId());
        bound(c, s.appKey(), s.configurationFingerprint());
        return c;
    }

    private void check(Session s, String platform) {
        if (!s.platformCode().equals(platform) || !s.expiresAt().isAfter(Instant.now()))
            throw new IllegalArgumentException("授权会话已过期，请重新授权");
        config(s);
    }

    private static boolean active(Session s) {
        return Set.of("CREATED", "REDIRECTED", "EXCHANGING", "PENDING_VERIFICATION")
                .contains(s.status());
    }

    private static void lease(Session current, Session expected) {
        if (!active(current)
                || expected.leaseId() == null
                || !expected.leaseId().equals(current.leaseId()))
            throw new IllegalStateException("授权处理已失效，请重新授权");
    }

    private static Status result(Session s) {
        return new Status(s.id(), s.status(), s.expiresAt(), s.errorMessage());
    }

    @Scheduled(fixedDelayString = "${rigour.thirdparty.oauth.verification-poll-ms:60000}")
    public void recover() {
        sessions.expire(Instant.now());
        sessions.purgeFinished(
                Instant.now().minusSeconds(86400L * Math.max(1, Math.min(365, retentionDays))));
        for (var before : sessions.pending(Instant.now(), 20))
            try {
                var claimed =
                        tx.execute(
                                status -> {
                                    tokens.lock(before.tokenId());
                                    var row = sessions.lock(before.id());
                                    if (!"PENDING_VERIFICATION".equals(row.status())
                                            || leased(row.leaseUntil(), Instant.now())
                                            || !row.expiresAt().isAfter(Instant.now())) return null;
                                    return sessions.update(
                                            row,
                                            row.progress(
                                                    row.status(),
                                                    row.browserBindingHash(),
                                                    row.codeHash(),
                                                    UUID.randomUUID(),
                                                    Instant.now().plusSeconds(90),
                                                    row.exchangeAttemptedAt(),
                                                    row.candidate(),
                                                    null,
                                                    row.errorCode(),
                                                    row.errorMessage()));
                                });
                if (claimed != null) verify(claimed);
            } catch (RuntimeException ignored) {
                /* 保留候选会话供短期重试，到期清理凭据。 */
            }
    }
}
