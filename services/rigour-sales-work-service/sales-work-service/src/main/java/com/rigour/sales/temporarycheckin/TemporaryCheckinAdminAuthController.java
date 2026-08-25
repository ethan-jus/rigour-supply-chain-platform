package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.AdminAccountView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.AdminMeView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.ChangePasswordRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.CityView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.CreateCityAdminRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.CreateCityRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.CreateCityResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.LoginRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.LoginResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.TemporaryCredentialView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthService.SessionGrant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 临时打卡后台应用登录、改密、退出、城市和城市管理员账号接口。 */
@RestController
@RequestMapping("/sales-checkin/admin/api/v1")
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
final class TemporaryCheckinAdminAuthController {

    private final TemporaryCheckinAdminAuthService authService;
    private final TemporaryCheckinAdminAccessPolicy accessPolicy;
    private final TemporaryCheckinAdminAuthProperties properties;
    private final Clock clock;

    TemporaryCheckinAdminAuthController(
            TemporaryCheckinAdminAuthService authService,
            TemporaryCheckinAdminAccessPolicy accessPolicy,
            TemporaryCheckinAdminAuthProperties properties,
            Clock clock) {
        this.authService = authService;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/auth/login")
    ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        requireSameOrigin(servletRequest);
        TemporaryCheckinRequestFacts facts = TemporaryCheckinRequestFacts.from(servletRequest);
        SessionGrant grant = authService.login(request == null ? null : request.username(),
                request == null ? null : request.password(), facts.clientIp(), facts.userAgent());
        return sessionResponse(grant);
    }

    @GetMapping("/auth/me")
    ResponseEntity<AdminMeView> me(HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(authService.me(accessPolicy.currentPrincipal(request)));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        authService.logout(accessPolicy.currentPrincipal(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @PostMapping("/auth/change-password")
    ResponseEntity<LoginResponse> changePassword(
            @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        TemporaryCheckinRequestFacts facts = TemporaryCheckinRequestFacts.from(request);
        SessionGrant grant = authService.changePassword(accessPolicy.currentPrincipal(request),
                body == null ? null : body.currentPassword(), body == null ? null : body.newPassword(),
                facts.clientIp(), facts.userAgent());
        return sessionResponse(grant);
    }

    @GetMapping("/cities")
    List<CityView> cities(HttpServletRequest request) {
        return authService.listCities(accessPolicy.currentPrincipal(request));
    }

    @PostMapping("/cities")
    CreateCityResponse createCity(@RequestBody CreateCityRequest body, HttpServletRequest request) {
        return authService.createCity(accessPolicy.currentPrincipal(request),
                body == null ? null : body.name(), body == null ? null : body.adminUsername());
    }

    @PostMapping("/admin-accounts")
    TemporaryCredentialView createCityAdmin(
            @RequestBody CreateCityAdminRequest body,
            HttpServletRequest request) {
        return authService.createCityAdmin(accessPolicy.currentPrincipal(request),
                body == null ? null : body.username(), body == null ? null : body.displayName(),
                body == null ? null : body.city());
    }

    @GetMapping("/admin-accounts")
    List<AdminAccountView> adminAccounts(HttpServletRequest request) {
        return authService.listAccounts(accessPolicy.currentPrincipal(request));
    }

    @PostMapping("/admin-accounts/{id}/password-reset")
    TemporaryCredentialView resetPassword(
            @PathVariable("id") UUID accountId,
            HttpServletRequest request) {
        return authService.resetPassword(accessPolicy.currentPrincipal(request), accountId);
    }

    private ResponseEntity<LoginResponse> sessionResponse(SessionGrant grant) {
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), grant.rawToken())
                .httpOnly(true).secure(true).sameSite("Strict").path("/")
                .maxAge(positiveDurationUntil(grant.expiresAt())).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new LoginResponse(authService.me(grant.principal())));
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(properties.getCookieName(), "")
                .httpOnly(true).secure(true).sameSite("Strict").path("/")
                .maxAge(Duration.ZERO).build();
    }

    private Duration positiveDurationUntil(Instant expiresAt) {
        Duration duration = Duration.between(clock.instant(), expiresAt);
        return duration.isNegative() || duration.isZero() ? Duration.ofSeconds(1) : duration;
    }

    private static void requireSameOrigin(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !fetchSite.isBlank()
                && !List.of("same-origin", "none").contains(fetchSite.toLowerCase())) {
            throw TemporaryCheckinException.adminForbidden("拒绝跨站登录请求");
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return;
        String scheme = firstText(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = firstText(request.getHeader("Host"), request.getServerName());
        String expected = scheme + "://" + host;
        if (!expected.equalsIgnoreCase(origin)) {
            throw TemporaryCheckinException.adminForbidden("拒绝跨站登录请求");
        }
    }

    private static String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first.trim();
    }
}
