package com.rigour.integration.application.port.out.thirdparty.oauth;

import com.rigour.integration.domain.thirdparty.oauth.*;

import java.time.Instant;
import java.util.*;

public interface ThirdPartyTokenStore {
    record Stored(
            UUID id,
            UUID tenantId,
            String platformCode,
            UUID connectorId,
            String appKey,
            OAuthToken token,
            String authStatus,
            Instant nextRefreshAt,
            Instant receivedAt,
            UUID refreshLeaseId,
            Instant refreshLeaseExpiresAt,
            int failureCount,
            long revision) {
        @Override
        public String toString() {
            return "StoredOAuthToken[REDACTED]";
        }
    }

    Stored ensure(OAuthConfiguration configuration, UUID actor);

    Optional<Stored> find(UUID tenant, String platform, UUID connector);

    Stored lock(UUID id);

    Stored activate(
            Stored expected,
            String appKey,
            OAuthToken token,
            Instant now,
            Instant nextRefresh,
            UUID actor,
            boolean authorization);

    boolean claimRefresh(Stored expected, UUID lease, Instant until);

    void refreshFailed(
            Stored expected,
            UUID lease,
            String status,
            String code,
            String message,
            Instant retryAt);

    List<Stored> due(Instant now, int limit);
}
