package com.rigour.tenant.iam.domain.model.credential;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 密码凭据的锁定状态；对象只保存强哈希，不接收或返回明文密码。 */
public record Credential(
        UUID id,
        String passwordHash,
        String algorithm,
        int algorithmVersion,
        int failedAttempts,
        Instant lockedUntil,
        Status status,
        long version
) {
    public Credential {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(passwordHash, "passwordHash cannot be null");
        Objects.requireNonNull(algorithm, "algorithm cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (algorithmVersion < 1 || failedAttempts < 0 || version < 0) {
            throw new IllegalArgumentException("credential versions and attempts are invalid");
        }
    }

    public boolean isAvailableAt(Instant now) {
        return status == Status.ACTIVE && (lockedUntil == null || !lockedUntil.isAfter(now));
    }

    public FailureState nextFailure(Instant now, int maximumAttempts, Duration lockDuration) {
        if (maximumAttempts < 1 || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("lock policy is invalid");
        }
        int attempts = Math.addExact(failedAttempts, 1);
        Instant newLockedUntil = attempts >= maximumAttempts ? now.plus(lockDuration) : null;
        return new FailureState(attempts, now, newLockedUntil);
    }

    public enum Status {
        ACTIVE,
        DISABLED
    }

    public record FailureState(int failedAttempts, Instant lastFailedAt, Instant lockedUntil) {
    }
}
