package com.rigour.integration.application.port.out.thirdparty.oauth;

import com.rigour.integration.domain.thirdparty.oauth.OAuthToken;

import java.time.Instant;
import java.util.*;

public interface ThirdPartyAuthorizationSessionStore {
    record Session(
            UUID id,
            UUID tenantId,
            String platformCode,
            UUID connectorId,
            UUID tokenId,
            UUID actorId,
            String appKey,
            String configurationFingerprint,
            String sessionHash,
            String browserBindingHash,
            String codeHash,
            String status,
            Instant expiresAt,
            UUID leaseId,
            Instant leaseUntil,
            Instant exchangeAttemptedAt,
            OAuthToken candidate,
            Instant finishedAt,
            String errorCode,
            String errorMessage,
            long revision) {
        @Override
        public String toString() {
            return "OAuthAuthorizationSession[id=" + id + ",status=" + status + "]";
        }

        public Session progress(
                String status,
                String browserHash,
                String codeHash,
                UUID lease,
                Instant leaseUntil,
                Instant attemptedAt,
                OAuthToken candidate,
                Instant finishedAt,
                String errorCode,
                String errorMessage) {
            return new Session(
                    id,
                    tenantId,
                    platformCode,
                    connectorId,
                    tokenId,
                    actorId,
                    appKey,
                    configurationFingerprint,
                    sessionHash,
                    browserHash,
                    codeHash,
                    status,
                    expiresAt,
                    lease,
                    leaseUntil,
                    attemptedAt,
                    candidate,
                    finishedAt,
                    errorCode,
                    errorMessage,
                    revision);
        }
    }

    void insert(Session session);

    Optional<Session> find(UUID id);

    Optional<Session> byHash(String hash);

    Session lock(UUID id);

    Session update(Session expected, Session changed);

    void supersede(UUID tokenId, Instant now);

    boolean active(UUID tokenId, Instant now);

    boolean processing(UUID tokenId, Instant now);

    List<Session> pending(Instant now, int limit);

    void expire(Instant now);

    void purgeFinished(Instant before);
}
