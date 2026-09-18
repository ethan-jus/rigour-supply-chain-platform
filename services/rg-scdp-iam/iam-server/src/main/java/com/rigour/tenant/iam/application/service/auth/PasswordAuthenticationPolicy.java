package com.rigour.tenant.iam.application.service.auth;

import java.time.Duration;

/** 账号锁定与会话绝对有效期；默认值在共享DEV压测和安全评审后才能冻结。 */
public record PasswordAuthenticationPolicy(
        int maximumFailedAttempts,
        Duration lockDuration,
        Duration sessionTimeToLive
) {
    public PasswordAuthenticationPolicy {
        if (maximumFailedAttempts < 1) {
            throw new IllegalArgumentException("maximumFailedAttempts must be positive");
        }
        requirePositive(lockDuration, "lockDuration");
        requirePositive(sessionTimeToLive, "sessionTimeToLive");
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
