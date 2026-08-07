package com.rigour.tenant.iam.application.port.out;

import java.util.Objects;

/** 使用一次性飞书授权码验证当前飞书用户；不向应用层暴露飞书访问令牌。 */
public interface FeishuIdentityProvider {

    VerifiedIdentity exchange(String code);

    record VerifiedIdentity(String tenantKey, String openId, String displayName, String avatarUrl) {
        public VerifiedIdentity {
            tenantKey = required(tenantKey, "tenantKey");
            openId = required(openId, "openId");
            displayName = normalized(displayName);
            avatarUrl = normalized(avatarUrl);
        }

        private static String required(String value, String field) {
            String normalized = normalized(value);
            return Objects.requireNonNull(normalized, field + " cannot be blank");
        }

        private static String normalized(String value) {
            if (value == null) return null;
            String normalized = value.strip();
            return normalized.isEmpty() ? null : normalized;
        }
    }
}
