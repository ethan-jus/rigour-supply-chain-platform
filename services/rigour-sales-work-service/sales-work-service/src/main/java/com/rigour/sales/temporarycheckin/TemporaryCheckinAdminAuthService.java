package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.AdminAccountView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.AdminMeView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.BootstrapAdminRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.BootstrapRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.BootstrapResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.CityView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.CreateCityResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.TemporaryCredentialView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthRepository.AccountRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthRepository.AuthenticatedSessionRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthRepository.CityRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 临时打卡后台账号、密码和不透明会话服务。 */
@Service
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinAdminAuthService {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TemporaryCheckinAdminAuthRepository repository;
    private final TemporaryCheckinAdminPasswordHasher passwordHasher;
    private final TemporaryCheckinAdminAuthProperties properties;
    private final Clock clock;
    private final UUID tenantId;

    TemporaryCheckinAdminAuthService(
            TemporaryCheckinAdminAuthRepository repository,
            TemporaryCheckinAdminPasswordHasher passwordHasher,
            TemporaryCheckinAdminAuthProperties properties,
            TemporaryCheckinProperties checkinProperties,
            Clock clock) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.properties = properties;
        this.clock = clock;
        this.tenantId = checkinProperties.requireTenantId();
        validateDurations(properties);
    }

    @Transactional
    SessionGrant login(String rawUsername, String rawPassword, String clientIp, String userAgent) {
        String username = normalizeUsername(rawUsername);
        char[] password = rawPassword == null ? new char[0] : rawPassword.toCharArray();
        Instant now = clock.instant();
        try {
            AccountRow account = repository.findAccountByUsername(tenantId, username).orElse(null);
            if (account == null) {
                passwordHasher.consumeDummyHash(password);
                throw invalidCredentials();
            }
            if (!"ACTIVE".equals(account.status())) {
                passwordHasher.consumeDummyHash(password);
                throw invalidCredentials();
            }
            if (account.lockedUntil() != null && account.lockedUntil().isAfter(now)) {
                passwordHasher.consumeDummyHash(password);
                throw TemporaryCheckinException.loginLocked("登录失败次数过多，请稍后再试");
            }
            if (!passwordHasher.matches(password, account.passwordHash())) {
                repository.recordLoginFailure(tenantId, account.id(), properties.getLoginFailureThreshold(), now,
                        now.plus(properties.getLoginLockDuration()));
                throw invalidCredentials();
            }
            if (account.mustChangePassword() && (account.temporaryPasswordExpiresAt() == null
                    || !account.temporaryPasswordExpiresAt().isAfter(now))) {
                throw TemporaryCheckinException.adminUnauthorized("临时密码已过期，请联系总管理员重置");
            }
            requireEnabledScope(account);
            repository.recordLoginSuccess(tenantId, account.id(), now);
            return createSession(account, clientIp, userAgent, now);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    TemporaryCheckinAdminPrincipal authenticate(String rawToken) {
        if (rawToken == null || rawToken.length() < 32 || rawToken.length() > 256) return null;
        Instant now = clock.instant();
        AuthenticatedSessionRow session = repository.findAuthenticatedSession(
                tenantId, sha256(rawToken), now).orElse(null);
        if (session == null) return null;
        if (session.lastSeenAt().plus(properties.getSessionTouchInterval()).isBefore(now)) {
            repository.touchSession(tenantId, session.sessionId(), now,
                    min(now.plus(properties.getIdleTimeout()), session.expiresAt()));
        }
        return principal(session);
    }

    AdminMeView me(TemporaryCheckinAdminPrincipal principal) {
        return meView(principal);
    }

    @Transactional
    void logout(TemporaryCheckinAdminPrincipal principal) {
        repository.revokeSession(tenantId, principal.sessionId(), clock.instant());
    }

    @Transactional
    SessionGrant changePassword(
            TemporaryCheckinAdminPrincipal principal, String rawCurrentPassword, String rawNewPassword,
            String clientIp, String userAgent) {
        char[] current = rawCurrentPassword == null ? new char[0] : rawCurrentPassword.toCharArray();
        char[] replacement = null;
        try {
            replacement = validatedNewPassword(rawNewPassword, principal.username());
            Instant now = clock.instant();
            AccountRow account = repository.findAccountByIdForUpdate(tenantId, principal.accountId())
                    .orElseThrow(() -> TemporaryCheckinException.adminUnauthorized("后台账号已失效"));
            if (!"ACTIVE".equals(account.status()) || !passwordHasher.matches(current, account.passwordHash())) {
                throw invalidCredentials();
            }
            if (passwordHasher.matches(replacement, account.passwordHash())) {
                throw TemporaryCheckinException.badRequest("新密码不能与当前密码相同");
            }
            String replacementHash = passwordHasher.hash(replacement);
            if (repository.replacePassword(tenantId, account.id(), replacementHash, false, null, now) != 1) {
                throw TemporaryCheckinException.conflict("账号状态已变化，请重新登录");
            }
            repository.revokeAllSessions(tenantId, account.id(), now);
            AccountRow updated = new AccountRow(account.id(), account.username(), account.displayName(),
                    account.role(), account.cityId(), account.city(), replacementHash, false, null,
                    account.passwordVersion() + 1, 0, null, account.status());
            return createSession(updated, clientIp, userAgent, now);
        } finally {
            Arrays.fill(current, '\0');
            if (replacement != null) Arrays.fill(replacement, '\0');
        }
    }

    List<CityView> listCities(TemporaryCheckinAdminPrincipal principal) {
        requireGlobal(principal);
        return repository.listCities(tenantId).stream().map(TemporaryCheckinAdminAuthService::cityView).toList();
    }

    List<AdminAccountView> listAccounts(TemporaryCheckinAdminPrincipal principal) {
        requireGlobal(principal);
        return repository.listAccounts(tenantId).stream()
                .map(account -> new AdminAccountView(
                        account.id(), account.username(), account.displayName(), account.role(), account.city(),
                        account.status(), account.mustChangePassword()))
                .toList();
    }

    @Transactional
    CreateCityResponse createCity(
            TemporaryCheckinAdminPrincipal principal, String rawName, String rawAdminUsername) {
        requireGlobal(principal);
        String city = normalizeCity(rawName);
        String username = normalizeUsername(rawAdminUsername);
        if (repository.findActiveCity(tenantId, city).isPresent()) {
            throw TemporaryCheckinException.conflict("城市已存在");
        }
        int sortOrder = Math.min(1_000_000, (repository.listCities(tenantId).size() + 1) * 10);
        CityRow cityRow = repository.ensureActiveCity(tenantId, city, sortOrder, clock.instant());
        TemporaryCredentialView credential = createAccount(
                username, city + "城市管理员", "CITY_ADMIN", cityRow, clock.instant());
        return new CreateCityResponse(cityView(cityRow), credential);
    }

    @Transactional
    TemporaryCredentialView createCityAdmin(
            TemporaryCheckinAdminPrincipal principal, String rawUsername, String rawDisplayName, String rawCity) {
        requireGlobal(principal);
        String city = normalizeCity(rawCity);
        CityRow cityRow = repository.findActiveCity(tenantId, city)
                .orElseThrow(() -> TemporaryCheckinException.badRequest("城市不存在或未启用"));
        return createAccount(normalizeUsername(rawUsername), normalizeDisplayName(rawDisplayName),
                "CITY_ADMIN", cityRow, clock.instant());
    }

    @Transactional
    TemporaryCredentialView resetPassword(
            TemporaryCheckinAdminPrincipal principal, UUID accountId) {
        requireGlobal(principal);
        if (accountId == null) throw TemporaryCheckinException.badRequest("accountId不能为空");
        AccountRow account = repository.findAccountByIdForUpdate(tenantId, accountId)
                .orElseThrow(() -> TemporaryCheckinException.notFound("城市管理员不存在"));
        if (!"CITY_ADMIN".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw TemporaryCheckinException.badRequest("仅支持重置启用中的城市管理员密码");
        }
        Instant now = clock.instant();
        String temporaryPassword = passwordHasher.generateTemporaryPassword();
        String hash = hashPassword(temporaryPassword);
        Instant expiresAt = now.plus(properties.getTemporaryPasswordTtl());
        if (repository.replacePassword(tenantId, account.id(), hash, true, expiresAt, now) != 1) {
            throw TemporaryCheckinException.conflict("账号状态已变化，请刷新后重试");
        }
        repository.revokeAllSessions(tenantId, account.id(), now);
        return credential(account.id(), account.username(), account.displayName(), account.role(), account.city(),
                temporaryPassword, expiresAt);
    }

    @Transactional
    BootstrapResponse bootstrap(BootstrapRequest request) {
        if (request == null) throw TemporaryCheckinException.badRequest("bootstrap请求不能为空");
        Instant now = clock.instant();
        List<String> importedCities = new ArrayList<>();
        int sortOrder = repository.listCities(tenantId).size() * 10;
        for (String rawCity : request.cities() == null ? List.<String>of() : request.cities()) {
            String city = normalizeCity(rawCity);
            CityRow row = repository.ensureActiveCity(tenantId, city, sortOrder += 10, now);
            importedCities.add(row.name());
        }
        List<TemporaryCredentialView> created = new ArrayList<>();
        for (BootstrapAdminRequest raw : request.accounts() == null
                ? List.<BootstrapAdminRequest>of() : request.accounts()) {
            String username = normalizeUsername(raw.username());
            if (repository.findAccountByUsername(tenantId, username).isPresent()) continue;
            String role = normalizeRole(raw.role());
            CityRow city = null;
            if ("CITY_ADMIN".equals(role)) {
                String cityName = normalizeCity(raw.city());
                city = repository.findActiveCity(tenantId, cityName)
                        .orElseGet(() -> repository.ensureActiveCity(
                                tenantId, cityName, repository.listCities(tenantId).size() * 10 + 10, now));
            }
            created.add(createAccount(username, normalizeDisplayName(raw.displayName()), role, city, now));
        }
        return new BootstrapResponse(List.copyOf(importedCities), List.copyOf(created));
    }

    private TemporaryCredentialView createAccount(
            String username, String displayName, String role, CityRow city, Instant now) {
        String temporaryPassword = passwordHasher.generateTemporaryPassword();
        String passwordHash = hashPassword(temporaryPassword);
        Instant expiresAt = now.plus(properties.getTemporaryPasswordTtl());
        UUID accountId = UUID.randomUUID();
        try {
            repository.insertAccount(tenantId, accountId, username, displayName, role,
                    city == null ? null : city.id(), passwordHash, expiresAt, now);
        } catch (DuplicateKeyException duplicate) {
            throw TemporaryCheckinException.conflict("管理员用户名已存在");
        }
        return credential(accountId, username, displayName, role, city == null ? null : city.name(),
                temporaryPassword, expiresAt);
    }

    private SessionGrant createSession(AccountRow account, String clientIp, String userAgent, Instant now) {
        UUID sessionId = UUID.randomUUID();
        String rawToken = randomToken();
        String csrfToken = randomToken();
        Instant expiresAt = now.plus(properties.getSessionDuration());
        Instant idleExpiresAt = min(now.plus(properties.getIdleTimeout()), expiresAt);
        repository.insertSession(tenantId, sessionId, account.id(), sha256(rawToken), csrfToken,
                account.passwordVersion(), optionalHash(clientIp), optionalHash(userAgent),
                now, idleExpiresAt, expiresAt);
        TemporaryCheckinAdminPrincipal principal = new TemporaryCheckinAdminPrincipal(
                account.id(), sessionId, account.username(), account.displayName(), account.role(),
                account.cityId(), account.city(), account.mustChangePassword(), csrfToken);
        return new SessionGrant(principal, rawToken, expiresAt);
    }

    private void requireEnabledScope(AccountRow account) {
        if ("GLOBAL_ADMIN".equals(account.role()) && account.cityId() == null) return;
        if ("CITY_ADMIN".equals(account.role()) && account.cityId() != null && account.city() != null
                && repository.existsActiveCity(tenantId, account.city())) return;
        throw TemporaryCheckinException.adminUnauthorized("后台账号城市未启用");
    }

    private static void requireGlobal(TemporaryCheckinAdminPrincipal principal) {
        if (principal == null || !principal.allCities()) {
            throw TemporaryCheckinException.adminForbidden("只有总管理员可以维护后台账号和城市");
        }
    }

    private static TemporaryCheckinException invalidCredentials() {
        return TemporaryCheckinException.adminUnauthorized("用户名或密码错误");
    }

    private static String normalizeUsername(String value) {
        String username = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw TemporaryCheckinException.badRequest("用户名必须为3到64位小写字母、数字、点、下划线或横线");
        }
        return username;
    }

    private static String normalizeCity(String value) {
        String city = value == null ? "" : value.trim();
        if (city.isEmpty() || city.length() > 64 || city.codePoints().anyMatch(Character::isISOControl)) {
            throw TemporaryCheckinException.badRequest("城市名称必须为1到64个有效字符");
        }
        return city;
    }

    private static String normalizeDisplayName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw TemporaryCheckinException.badRequest("管理员名称必须为1到128个有效字符");
        }
        return name;
    }

    private static String normalizeRole(String value) {
        String role = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("GLOBAL_ADMIN", "CITY_ADMIN").contains(role)) {
            throw TemporaryCheckinException.badRequest("role仅支持GLOBAL_ADMIN或CITY_ADMIN");
        }
        return role;
    }

    private static char[] validatedNewPassword(String value, String username) {
        if (value == null || value.length() < 12 || value.length() > 128 || value.isBlank()) {
            throw TemporaryCheckinException.badRequest("新密码长度必须为12到128位");
        }
        boolean hasUppercase = value.chars().anyMatch(character -> character >= 'A' && character <= 'Z');
        boolean hasLowercase = value.chars().anyMatch(character -> character >= 'a' && character <= 'z');
        boolean hasDigit = value.chars().anyMatch(character -> character >= '0' && character <= '9');
        if (!hasUppercase || !hasLowercase || !hasDigit) {
            throw TemporaryCheckinException.badRequest("新密码必须同时包含大写字母、小写字母和数字");
        }
        if (value.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
            throw TemporaryCheckinException.badRequest("新密码不能包含用户名");
        }
        return value.toCharArray();
    }

    private static AdminMeView meView(TemporaryCheckinAdminPrincipal principal) {
        return new AdminMeView(principal.accountId(), principal.username(), principal.displayName(),
                principal.role(), principal.city(), principal.allCities(), principal.mustChangePassword(),
                true, principal.allCities(), principal.csrfToken());
    }

    private static TemporaryCheckinAdminPrincipal principal(AuthenticatedSessionRow row) {
        return new TemporaryCheckinAdminPrincipal(row.accountId(), row.sessionId(), row.username(),
                row.displayName(), row.role(), row.cityId(), row.city(), row.mustChangePassword(), row.csrfToken());
    }

    private static CityView cityView(CityRow row) {
        return new CityView(row.id(), row.name(), row.status(), row.sortOrder());
    }

    private static TemporaryCredentialView credential(
            UUID id, String username, String displayName, String role, String city,
            String temporaryPassword, Instant expiresAt) {
        return new TemporaryCredentialView(
                id, username, displayName, role, city, temporaryPassword, expiresAt);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashPassword(String value) {
        char[] password = value.toCharArray();
        try {
            return passwordHasher.hash(password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String optionalHash(String value) {
        return value == null || value.isBlank() ? null : sha256(value.trim());
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK不支持SHA-256", exception);
        }
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static void validateDurations(TemporaryCheckinAdminAuthProperties value) {
        for (Duration duration : List.of(value.getSessionDuration(), value.getIdleTimeout(),
                value.getTemporaryPasswordTtl(), value.getLoginLockDuration(), value.getSessionTouchInterval())) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalStateException("后台认证时间参数必须大于0");
            }
        }
        if (value.getLoginFailureThreshold() < 2 || value.getLoginFailureThreshold() > 20) {
            throw new IllegalStateException("后台登录失败锁定阈值必须在2到20之间");
        }
    }

    record SessionGrant(TemporaryCheckinAdminPrincipal principal, String rawToken, Instant expiresAt) { }
}
