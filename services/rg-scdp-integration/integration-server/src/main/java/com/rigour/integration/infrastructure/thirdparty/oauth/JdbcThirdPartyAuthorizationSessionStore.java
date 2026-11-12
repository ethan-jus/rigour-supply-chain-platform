package com.rigour.integration.infrastructure.thirdparty.oauth;

import static com.rigour.integration.infrastructure.thirdparty.oauth.OAuthJdbcValues.*;

import com.rigour.integration.application.port.out.thirdparty.oauth.ThirdPartyAuthorizationSessionStore;
import com.rigour.integration.domain.thirdparty.oauth.OAuthToken;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcThirdPartyAuthorizationSessionStore
        implements ThirdPartyAuthorizationSessionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcThirdPartyAuthorizationSessionStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(Session s) {
        jdbc.update(
                "INSERT INTO"
                    + " integration_oauth_authorization_session(id,tenant_id,platform_code,connector_id,token_id,actor_id,app_key,configuration_fingerprint,session_hash,status,expires_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                bytes(s.id()),
                bytes(s.tenantId()),
                s.platformCode(),
                bytes(s.connectorId()),
                bytes(s.tokenId()),
                bytes(s.actorId()),
                s.appKey(),
                s.configurationFingerprint(),
                s.sessionHash(),
                s.status(),
                utc(s.expiresAt()));
    }

    @Override
    public Optional<Session> find(UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM integration_oauth_authorization_session WHERE id=?",
                        this::row,
                        bytes(id))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Session> byHash(String hash) {
        return jdbc
                .query(
                        "SELECT * FROM integration_oauth_authorization_session WHERE"
                                + " session_hash=?",
                        this::row,
                        hash)
                .stream()
                .findFirst();
    }

    @Override
    public Session lock(UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM integration_oauth_authorization_session WHERE id=? FOR"
                                + " UPDATE",
                        this::row,
                        bytes(id))
                .stream()
                .findFirst()
                .orElseThrow(OAuthJdbcValues::conflict);
    }

    @Override
    public Session update(Session before, Session s) {
        int n =
                jdbc.update(
                        "UPDATE integration_oauth_authorization_session SET"
                            + " browser_binding_hash=?,authorization_code_hash=?,status=?,processing_lease_id=?,processing_lease_expires_at=?,exchange_attempted_at=?,candidate_token_json=?,finished_at=?,error_code=?,error_message=?,revision=revision+1"
                            + " WHERE id=? AND revision=?",
                        s.browserBindingHash(),
                        s.codeHash(),
                        s.status(),
                        text(s.leaseId()),
                        utc(s.leaseUntil()),
                        utc(s.exchangeAttemptedAt()),
                        s.candidate() == null ? null : json.writeValueAsString(s.candidate()),
                        utc(s.finishedAt()),
                        s.errorCode(),
                        s.errorMessage(),
                        bytes(before.id()),
                        before.revision());
        if (n != 1) throw conflict();
        return find(before.id()).orElseThrow(OAuthJdbcValues::conflict);
    }

    @Override
    public void supersede(UUID tokenId, Instant now) {
        jdbc.update(
                "UPDATE integration_oauth_authorization_session SET"
                    + " status='SUPERSEDED',candidate_token_json=NULL,processing_lease_id=NULL,processing_lease_expires_at=NULL,finished_at=?,revision=revision+1"
                    + " WHERE token_id=? AND status IN"
                    + " ('CREATED','REDIRECTED','PENDING_VERIFICATION','EXCHANGING')",
                utc(now),
                bytes(tokenId));
    }

    @Override
    public boolean active(UUID tokenId, Instant now) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM integration_oauth_authorization_session WHERE"
                                + " token_id=? AND expires_at>? AND status IN"
                                + " ('CREATED','REDIRECTED','PENDING_VERIFICATION','EXCHANGING')",
                        Long.class,
                        bytes(tokenId),
                        utc(now))
                > 0;
    }

    @Override
    public List<Session> pending(Instant now, int limit) {
        return jdbc.query(
                "SELECT * FROM integration_oauth_authorization_session WHERE"
                        + " status='PENDING_VERIFICATION' AND candidate_token_json IS NOT NULL AND"
                        + " expires_at>? AND (processing_lease_id IS NULL OR"
                        + " processing_lease_expires_at<?) ORDER BY created_at LIMIT ?",
                this::row,
                utc(now),
                utc(now),
                limit);
    }

    @Override
    public boolean processing(UUID tokenId, Instant now) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM integration_oauth_authorization_session WHERE"
                            + " token_id=? AND status IN ('EXCHANGING','PENDING_VERIFICATION') AND"
                            + " processing_lease_expires_at>?",
                        Long.class,
                        bytes(tokenId),
                        utc(now))
                > 0;
    }

    @Override
    public void expire(Instant now) {
        jdbc.update(
                "UPDATE integration_oauth_authorization_session SET"
                    + " status='EXPIRED',candidate_token_json=NULL,processing_lease_id=NULL,processing_lease_expires_at=NULL,finished_at=?,revision=revision+1"
                    + " WHERE expires_at<=? AND status IN"
                    + " ('CREATED','REDIRECTED','EXCHANGING','PENDING_VERIFICATION') AND"
                    + " (processing_lease_id IS NULL OR processing_lease_expires_at<=?)",
                utc(now),
                utc(now),
                utc(now));
        jdbc.update(
                "UPDATE integration_oauth_authorization_session SET candidate_token_json=NULL WHERE"
                        + " status IN ('FAILED','UNCERTAIN','SUPERSEDED','EXPIRED','SUCCEEDED') AND"
                        + " candidate_token_json IS NOT NULL");
    }

    @Override
    public void purgeFinished(Instant before) {
        jdbc.update(
                "DELETE FROM integration_oauth_authorization_session WHERE finished_at<? AND"
                    + " candidate_token_json IS NULL AND status IN"
                    + " ('FAILED','UNCERTAIN','SUPERSEDED','EXPIRED','SUCCEEDED')",
                utc(before));
    }

    private Session row(ResultSet r, int index) throws SQLException {
        String candidate = r.getString("candidate_token_json");
        return new Session(
                uuid(r, "id"),
                uuid(r, "tenant_id"),
                r.getString("platform_code"),
                uuid(r, "connector_id"),
                uuid(r, "token_id"),
                uuid(r, "actor_id"),
                r.getString("app_key"),
                r.getString("configuration_fingerprint"),
                r.getString("session_hash"),
                r.getString("browser_binding_hash"),
                r.getString("authorization_code_hash"),
                r.getString("status"),
                instant(r, "expires_at"),
                lease(r, "processing_lease_id"),
                instant(r, "processing_lease_expires_at"),
                instant(r, "exchange_attempted_at"),
                candidate == null ? null : json.readValue(candidate, OAuthToken.class),
                instant(r, "finished_at"),
                r.getString("error_code"),
                r.getString("error_message"),
                r.getLong("revision"));
    }
}
