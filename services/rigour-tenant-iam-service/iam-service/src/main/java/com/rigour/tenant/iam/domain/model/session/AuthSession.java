package com.rigour.tenant.iam.domain.model.session;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** 平台管理员与租户用户共用的认证会话。 */
public record AuthSession(
        UUID id,
        PrincipalScope principalScope,
        UUID tenantId,
        UUID principalId,
        ClientType clientType,
        String deviceName,
        byte[] clientFingerprintHash,
        byte[] userAgentHash,
        byte[] ipAddress,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant expiresAt
) {
    public AuthSession {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(principalScope, "principalScope cannot be null");
        Objects.requireNonNull(principalId, "principalId cannot be null");
        Objects.requireNonNull(clientType, "clientType cannot be null");
        Objects.requireNonNull(issuedAt, "issuedAt cannot be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
        if ((principalScope == PrincipalScope.TENANT) != (tenantId != null)) {
            throw new IllegalArgumentException("tenant session must have tenantId and platform session must not");
        }
        if (lastSeenAt.isBefore(issuedAt) || expiresAt.compareTo(issuedAt) <= 0
                || lastSeenAt.isAfter(expiresAt)) {
            throw new IllegalArgumentException("session time range is invalid");
        }
        clientFingerprintHash = copyHash(clientFingerprintHash, "clientFingerprintHash");
        userAgentHash = copyHash(userAgentHash, "userAgentHash");
        ipAddress = copyAddress(ipAddress);
    }

    @Override
    public byte[] clientFingerprintHash() {
        return copy(clientFingerprintHash);
    }

    @Override
    public byte[] userAgentHash() {
        return copy(userAgentHash);
    }

    @Override
    public byte[] ipAddress() {
        return copy(ipAddress);
    }

    private static byte[] copyHash(byte[] value, String field) {
        if (value != null && value.length != 32) {
            throw new IllegalArgumentException(field + " must be SHA-256 bytes");
        }
        return copy(value);
    }

    private static byte[] copyAddress(byte[] value) {
        if (value != null && value.length != 4 && value.length != 16) {
            throw new IllegalArgumentException("ipAddress must be IPv4 or IPv6 bytes");
        }
        return copy(value);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    public enum PrincipalScope {
        PLATFORM,
        TENANT
    }

    public enum ClientType {
        WEB,
        FEISHU_H5
    }
}
