package com.rigour.sales.temporarycheckin;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** 受 Nginx Basic Auth 保护的管理页入口；数据接口仍单独校验 Nginx 注入的账号范围。 */
@Controller
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinAdminPageController {

    private static final Duration SWITCH_CHALLENGE_TTL = Duration.ofMinutes(2);
    private static final int MAX_PENDING_SWITCHES = 128;
    private static final String BASIC_AUTH_CHALLENGE = "Basic realm=\"Sales Check-in Admin\"";

    private final TemporaryCheckinAdminAccessPolicy accessPolicy;
    private final Map<UUID, AccountSwitchChallenge> pendingSwitches = new ConcurrentHashMap<>();

    public TemporaryCheckinAdminPageController(TemporaryCheckinAdminAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    @GetMapping(value = "/sales-checkin/admin/", params = "!switchChallenge")
    public String page() {
        return "forward:/sales-checkin/admin/index.html";
    }

    /**
     * 创建一次性账号切换挑战。挑战只记录原账号，不授予任何后台权限。
     */
    @PostMapping("/sales-checkin/admin/account-switches")
    public ResponseEntity<Void> beginAccountSwitch(
            @RequestHeader(value = TemporaryCheckinAdminAccessPolicy.HEADER, required = false)
            String rawUsername) {
        var scope = accessPolicy.requireScope(rawUsername);
        UUID challengeId = registerChallenge(scope.username(), Instant.now());
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/sales-checkin/admin/?switchChallenge=" + challengeId))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /**
     * 在后台根保护空间返回同 realm 的 401，使浏览器重新询问 Basic Auth 凭据。
     * 只有与原账号不同且仍通过 Nginx 校验的管理账号才能消费挑战。
     */
    @GetMapping(value = "/sales-checkin/admin/", params = "switchChallenge")
    public ResponseEntity<Void> completeAccountSwitch(
            @RequestParam UUID switchChallenge,
            @RequestHeader(value = TemporaryCheckinAdminAccessPolicy.HEADER, required = false)
            String rawUsername) {
        var scope = accessPolicy.requireScope(rawUsername);
        Instant now = Instant.now();
        AccountSwitchChallenge challenge = pendingSwitches.get(switchChallenge);
        if (challenge == null || !challenge.expiresAt().isAfter(now)) {
            pendingSwitches.remove(switchChallenge);
            return switchRedirect("expired");
        }
        if (challenge.originalUsername().equals(scope.username())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, BASIC_AUTH_CHALLENGE)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        if (!pendingSwitches.remove(switchChallenge, challenge)) {
            return switchRedirect("expired");
        }
        return switchRedirect("complete");
    }

    private synchronized UUID registerChallenge(String username, Instant now) {
        pendingSwitches.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        while (pendingSwitches.size() >= MAX_PENDING_SWITCHES) {
            pendingSwitches.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .ifPresent(entry -> pendingSwitches.remove(entry.getKey(), entry.getValue()));
        }
        UUID challengeId = UUID.randomUUID();
        pendingSwitches.put(challengeId,
                new AccountSwitchChallenge(username, now.plus(SWITCH_CHALLENGE_TTL)));
        return challengeId;
    }

    private static ResponseEntity<Void> switchRedirect(String result) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/sales-checkin/admin/?switch=" + result))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private record AccountSwitchChallenge(String originalUsername, Instant expiresAt) { }
}
