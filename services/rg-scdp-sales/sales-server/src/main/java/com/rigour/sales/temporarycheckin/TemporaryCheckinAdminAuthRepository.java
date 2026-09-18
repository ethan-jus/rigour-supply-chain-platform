package com.rigour.sales.temporarycheckin;

import static com.rigour.sales.infrastructure.persistence.SalesUuidCodec.decode;
import static com.rigour.sales.infrastructure.persistence.SalesUuidCodec.encode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 临时打卡后台城市、账号和不透明会话仓储。 */
@Repository
public class TemporaryCheckinAdminAuthRepository {

    private final JdbcTemplate jdbc;

    public TemporaryCheckinAdminAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> listActiveCities(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT name FROM temp_sales_checkin_city
                 WHERE tenant_id=? AND status='ACTIVE'
                 ORDER BY sort_order, name, id
                """, String.class, encode(tenantId));
    }

    List<CityRow> listCities(UUID tenantId) {
        return jdbc.query("""
                SELECT id, name, status, sort_order FROM temp_sales_checkin_city
                 WHERE tenant_id=? ORDER BY status, sort_order, name, id
                """, (rs, row) -> city(rs), encode(tenantId));
    }

    public boolean existsActiveCity(UUID tenantId, String city) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_city
                 WHERE tenant_id=? AND name=? AND status='ACTIVE'
                """, Integer.class, encode(tenantId), city);
        return count != null && count > 0;
    }

    Optional<CityRow> findActiveCity(UUID tenantId, String city) {
        return jdbc.query("""
                SELECT id, name, status, sort_order FROM temp_sales_checkin_city
                 WHERE tenant_id=? AND name=? AND status='ACTIVE' LIMIT 1
                """, (rs, row) -> city(rs), encode(tenantId), city).stream().findFirst();
    }

    CityRow ensureActiveCity(UUID tenantId, String city, int sortOrder, Instant now) {
        CityRow existing = findActiveCity(tenantId, city).orElse(null);
        if (existing != null) return existing;
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO temp_sales_checkin_city
                        (id, tenant_id, name, status, sort_order, created_at, updated_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                    """, encode(id), encode(tenantId), city, sortOrder, timestamp(now), timestamp(now));
        } catch (DuplicateKeyException concurrentInsert) {
            // 同名城市由另一请求先创建时，读取唯一键对应行即可。
        }
        return findActiveCity(tenantId, city)
                .orElseThrow(() -> new IllegalStateException("城市目录创建后不可见"));
    }

    Optional<AccountRow> findAccountByUsername(UUID tenantId, String username) {
        return jdbc.query(accountSelect() + """
                 WHERE a.tenant_id=? AND a.username=? LIMIT 1
                """, (rs, row) -> account(rs), encode(tenantId), username).stream().findFirst();
    }

    List<AccountRow> listAccounts(UUID tenantId) {
        return jdbc.query(accountSelect() + """
                 WHERE a.tenant_id=?
                 ORDER BY a.role, c.sort_order, c.name, a.username, a.id
                """, (rs, row) -> account(rs), encode(tenantId));
    }

    Optional<AccountRow> findAccountByIdForUpdate(UUID tenantId, UUID accountId) {
        return jdbc.query(accountSelect() + """
                 WHERE a.tenant_id=? AND a.id=? LIMIT 1 FOR UPDATE
                """, (rs, row) -> account(rs), encode(tenantId), encode(accountId)).stream().findFirst();
    }

    void insertAccount(
            UUID tenantId, UUID accountId, String username, String displayName, String role, UUID cityId,
            String passwordHash, Instant temporaryPasswordExpiresAt, Instant now) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_admin_account
                    (id, tenant_id, username, display_name, role, city_id, password_hash,
                     must_change_password, temporary_password_expires_at, password_version,
                     failed_login_attempts, locked_until, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 1, 0, NULL, 'ACTIVE', ?, ?)
                """, encode(accountId), encode(tenantId), username, displayName, role, encode(cityId),
                passwordHash, timestamp(temporaryPasswordExpiresAt), timestamp(now), timestamp(now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(
            UUID tenantId, UUID accountId, int threshold, Instant now, Instant lockedUntil) {
        jdbc.update("""
                UPDATE temp_sales_checkin_admin_account
                   SET failed_login_attempts=
                         CASE WHEN locked_until IS NOT NULL AND locked_until<=? THEN 1
                              ELSE failed_login_attempts+1 END,
                       locked_until=NULL,
                       updated_at=?
                 WHERE tenant_id=? AND id=?
                """, timestamp(now), timestamp(now),
                encode(tenantId), encode(accountId));
        jdbc.update("""
                UPDATE temp_sales_checkin_admin_account
                   SET locked_until=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND failed_login_attempts>=?
                """, timestamp(lockedUntil), timestamp(now), encode(tenantId), encode(accountId), threshold);
    }

    void recordLoginSuccess(UUID tenantId, UUID accountId, Instant now) {
        jdbc.update("""
                UPDATE temp_sales_checkin_admin_account
                   SET failed_login_attempts=0, locked_until=NULL, last_login_at=?, updated_at=?
                 WHERE tenant_id=? AND id=?
                """, timestamp(now), timestamp(now), encode(tenantId), encode(accountId));
    }

    int replacePassword(
            UUID tenantId, UUID accountId, String passwordHash, boolean mustChange,
            Instant temporaryExpiresAt, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_admin_account
                   SET password_hash=?, must_change_password=?, temporary_password_expires_at=?,
                       password_version=password_version+1, failed_login_attempts=0,
                       locked_until=NULL, updated_at=?
                 WHERE tenant_id=? AND id=? AND status='ACTIVE'
                """, passwordHash, mustChange ? 1 : 0, timestamp(temporaryExpiresAt), timestamp(now),
                encode(tenantId), encode(accountId));
    }

    void insertSession(
            UUID tenantId, UUID sessionId, UUID accountId, String tokenHash, String csrfToken,
            long passwordVersion, String clientIpHash, String userAgentHash,
            Instant now, Instant idleExpiresAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_admin_session
                    (id, tenant_id, account_id, token_hash, csrf_token, password_version,
                     client_ip_hash, user_agent_hash, created_at, last_seen_at,
                     idle_expires_at, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """, encode(sessionId), encode(tenantId), encode(accountId), tokenHash, csrfToken,
                passwordVersion, clientIpHash, userAgentHash, timestamp(now), timestamp(now),
                timestamp(idleExpiresAt), timestamp(expiresAt));
    }

    Optional<AuthenticatedSessionRow> findAuthenticatedSession(
            UUID tenantId, String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT s.id AS session_id, s.last_seen_at, s.expires_at, s.csrf_token,
                       a.id AS account_id, a.username, a.display_name, a.role, a.city_id,
                       c.name AS city, a.must_change_password
                  FROM temp_sales_checkin_admin_session s
                  JOIN temp_sales_checkin_admin_account a
                    ON a.tenant_id=s.tenant_id AND a.id=s.account_id
             LEFT JOIN temp_sales_checkin_city c
                    ON c.tenant_id=a.tenant_id AND c.id=a.city_id
                 WHERE s.tenant_id=? AND s.token_hash=? AND s.revoked_at IS NULL
                   AND s.expires_at>? AND s.idle_expires_at>?
                   AND a.status='ACTIVE' AND a.password_version=s.password_version
                   AND (a.role='GLOBAL_ADMIN' OR (c.status='ACTIVE' AND c.id IS NOT NULL))
                 LIMIT 1
                """, (rs, row) -> authenticatedSession(rs), encode(tenantId), tokenHash,
                timestamp(now), timestamp(now)).stream().findFirst();
    }

    void touchSession(UUID tenantId, UUID sessionId, Instant now, Instant idleExpiresAt) {
        jdbc.update("""
                UPDATE temp_sales_checkin_admin_session
                   SET last_seen_at=?, idle_expires_at=LEAST(expires_at, ?)
                 WHERE tenant_id=? AND id=? AND revoked_at IS NULL
                """, timestamp(now), timestamp(idleExpiresAt), encode(tenantId), encode(sessionId));
    }

    void revokeSession(UUID tenantId, UUID sessionId, Instant now) {
        jdbc.update("""
                UPDATE temp_sales_checkin_admin_session SET revoked_at=?
                 WHERE tenant_id=? AND id=? AND revoked_at IS NULL
                """, timestamp(now), encode(tenantId), encode(sessionId));
    }

    void revokeAllSessions(UUID tenantId, UUID accountId, Instant now) {
        jdbc.update("""
                UPDATE temp_sales_checkin_admin_session SET revoked_at=?
                 WHERE tenant_id=? AND account_id=? AND revoked_at IS NULL
                """, timestamp(now), encode(tenantId), encode(accountId));
    }

    private static String accountSelect() {
        return """
                SELECT a.id, a.username, a.display_name, a.role, a.city_id, c.name AS city,
                       a.password_hash, a.must_change_password, a.temporary_password_expires_at,
                       a.password_version, a.failed_login_attempts, a.locked_until, a.status
                  FROM temp_sales_checkin_admin_account a
             LEFT JOIN temp_sales_checkin_city c
                    ON c.tenant_id=a.tenant_id AND c.id=a.city_id
                """;
    }

    private static CityRow city(ResultSet rs) throws SQLException {
        return new CityRow(decode(rs.getBytes("id")), rs.getString("name"), rs.getString("status"),
                rs.getInt("sort_order"));
    }

    private static AccountRow account(ResultSet rs) throws SQLException {
        return new AccountRow(decode(rs.getBytes("id")), rs.getString("username"),
                rs.getString("display_name"), rs.getString("role"), decode(rs.getBytes("city_id")),
                rs.getString("city"), rs.getString("password_hash"),
                rs.getBoolean("must_change_password"), instant(rs, "temporary_password_expires_at"),
                rs.getLong("password_version"), rs.getInt("failed_login_attempts"),
                instant(rs, "locked_until"), rs.getString("status"));
    }

    private static AuthenticatedSessionRow authenticatedSession(ResultSet rs) throws SQLException {
        return new AuthenticatedSessionRow(decode(rs.getBytes("session_id")),
                instant(rs, "last_seen_at"), instant(rs, "expires_at"), rs.getString("csrf_token"),
                decode(rs.getBytes("account_id")), rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), decode(rs.getBytes("city_id")), rs.getString("city"),
                rs.getBoolean("must_change_password"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record CityRow(UUID id, String name, String status, int sortOrder) { }

    record AccountRow(
            UUID id, String username, String displayName, String role, UUID cityId, String city,
            String passwordHash, boolean mustChangePassword, Instant temporaryPasswordExpiresAt,
            long passwordVersion, int failedLoginAttempts, Instant lockedUntil, String status) { }

    record AuthenticatedSessionRow(
            UUID sessionId, Instant lastSeenAt, Instant expiresAt, String csrfToken,
            UUID accountId, String username, String displayName, String role, UUID cityId, String city,
            boolean mustChangePassword) { }
}
