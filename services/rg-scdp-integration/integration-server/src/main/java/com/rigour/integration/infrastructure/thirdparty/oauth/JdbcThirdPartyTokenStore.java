package com.rigour.integration.infrastructure.thirdparty.oauth;

import static com.rigour.integration.infrastructure.thirdparty.oauth.OAuthJdbcValues.*;

import com.rigour.integration.application.port.out.thirdparty.oauth.ThirdPartyTokenStore;
import com.rigour.integration.domain.thirdparty.oauth.*;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 凭据仅在后台仓储内读取，禁止配置SQL参数级日志。 */
@Repository
public class JdbcThirdPartyTokenStore implements ThirdPartyTokenStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcThirdPartyTokenStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Stored ensure(OAuthConfiguration c, UUID actor) {
        var existing = find(c.tenantId(), c.platformCode(), c.connectorId());
        if (existing.isPresent()) return existing.get();
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " integration_oauth_token(id,tenant_id,platform_code,connector_id,app_key,created_by)"
                        + " VALUES(?,?,?,?,?,?)",
                    bytes(UUID.randomUUID()),
                    bytes(c.tenantId()),
                    c.platformCode(),
                    bytes(c.connectorId()),
                    c.appKey(),
                    bytes(actor));
        } catch (DuplicateKeyException race) {
            /* 唯一键保证同连接只初始化一次。 */
        }
        return find(c.tenantId(), c.platformCode(), c.connectorId())
                .orElseThrow(OAuthJdbcValues::conflict);
    }

    @Override
    public Optional<Stored> find(UUID tenant, String platform, UUID connector) {
        return jdbc
                .query(
                        "SELECT * FROM integration_oauth_token WHERE tenant_id=? AND"
                                + " platform_code=? AND connector_id=?",
                        this::row,
                        bytes(tenant),
                        platform,
                        bytes(connector))
                .stream()
                .findFirst();
    }

    @Override
    public Stored lock(UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM integration_oauth_token WHERE id=? FOR UPDATE",
                        this::row,
                        bytes(id))
                .stream()
                .findFirst()
                .orElseThrow(OAuthJdbcValues::conflict);
    }

    @Override
    public Stored activate(
            Stored before,
            String appKey,
            OAuthToken token,
            Instant now,
            Instant next,
            UUID actor,
            boolean authorization) {
        int n =
                jdbc.update(
                        "UPDATE integration_oauth_token SET"
                            + " app_key=?,access_token=?,refresh_token=?,token_type=?,token_metadata_json=?,token_received_at=?,access_token_expires_at=?,refresh_token_expires_at=?,next_refresh_at=?,auth_status='AUTHORIZED',last_authorized_at=CASE"
                            + " WHEN ? THEN ? ELSE last_authorized_at END,last_refreshed_at=CASE"
                            + " WHEN ? THEN last_refreshed_at ELSE ?"
                            + " END,refresh_failure_count=0,last_error_code=NULL,last_error_message=NULL,refresh_lease_id=NULL,refresh_lease_expires_at=NULL,updated_by=?,revision=revision+1"
                            + " WHERE id=? AND tenant_id=? AND platform_code=? AND connector_id=?"
                            + " AND revision=? AND COALESCE(refresh_lease_id,'')=?",
                        appKey,
                        token.accessToken(),
                        token.refreshToken(),
                        token.tokenType(),
                        json.writeValueAsString(token.metadata()),
                        utc(token.receivedAt() == null ? now : token.receivedAt()),
                        utc(token.expiresAt()),
                        utc(token.refreshExpiresAt()),
                        utc(next),
                        authorization,
                        utc(now),
                        authorization,
                        utc(now),
                        bytes(actor),
                        bytes(before.id()),
                        bytes(before.tenantId()),
                        before.platformCode(),
                        bytes(before.connectorId()),
                        before.revision(),
                        before.refreshLeaseId() == null ? "" : before.refreshLeaseId().toString());
        if (n != 1) throw conflict();
        return lock(before.id());
    }

    @Override
    public boolean claimRefresh(Stored before, UUID lease, Instant until) {
        return jdbc.update(
                        "UPDATE integration_oauth_token SET"
                            + " refresh_lease_id=?,refresh_lease_expires_at=? WHERE id=? AND"
                            + " revision=? AND auth_status='AUTHORIZED' AND (refresh_lease_id IS"
                            + " NULL OR refresh_lease_expires_at<?)",
                        lease.toString(),
                        utc(until),
                        bytes(before.id()),
                        before.revision(),
                        utc(Instant.now()))
                == 1;
    }

    @Override
    public void refreshFailed(
            Stored before, UUID lease, String status, String code, String message, Instant retry) {
        jdbc.update(
                "UPDATE integration_oauth_token SET"
                    + " auth_status=?,refresh_failure_count=refresh_failure_count+1,last_error_code=?,last_error_message=?,next_refresh_at=?,refresh_lease_id=NULL,refresh_lease_expires_at=NULL,revision=revision+1"
                    + " WHERE id=? AND revision=? AND refresh_lease_id=?",
                status,
                code,
                message,
                utc(retry),
                bytes(before.id()),
                before.revision(),
                lease.toString());
    }

    @Override
    public List<Stored> due(Instant now, int limit) {
        return jdbc.query(
                "SELECT * FROM integration_oauth_token WHERE auth_status='AUTHORIZED' AND"
                        + " next_refresh_at<=? ORDER BY next_refresh_at LIMIT ?",
                this::row,
                utc(now),
                limit);
    }

    @SuppressWarnings("unchecked")
    private Stored row(ResultSet r, int index) throws SQLException {
        String raw = r.getString("token_metadata_json");
        Map<String, Object> metadata = raw == null ? Map.of() : json.readValue(raw, Map.class);
        var token =
                new OAuthToken(
                        r.getString("access_token"),
                        r.getString("refresh_token"),
                        instant(r, "access_token_expires_at"),
                        r.getString("token_type"),
                        instant(r, "refresh_token_expires_at"),
                        metadata,
                        instant(r, "token_received_at"));
        return new Stored(
                uuid(r, "id"),
                uuid(r, "tenant_id"),
                r.getString("platform_code"),
                uuid(r, "connector_id"),
                r.getString("app_key"),
                token,
                r.getString("auth_status"),
                instant(r, "next_refresh_at"),
                instant(r, "token_received_at"),
                lease(r, "refresh_lease_id"),
                instant(r, "refresh_lease_expires_at"),
                r.getInt("refresh_failure_count"),
                r.getLong("revision"));
    }
}
