package com.rigour.tenant.iam.application.service.auth;

import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import java.util.Objects;
import java.util.UUID;

/** 事务内认证结果；失败结果先提交计数，再由外层转换成统一认证异常。 */
public sealed interface PasswordAuthenticationAttempt {

    record Success(
            UUID sessionId,
            PrincipalScope principalScope,
            UUID tenantId,
            UUID principalId,
            String displayName,
            String platformRole,
            long securityVersion
    ) implements PasswordAuthenticationAttempt {
        public Success {
            Objects.requireNonNull(sessionId, "sessionId cannot be null");
            Objects.requireNonNull(principalScope, "principalScope cannot be null");
            Objects.requireNonNull(principalId, "principalId cannot be null");
            Objects.requireNonNull(displayName, "displayName cannot be null");
        }
    }

    record Failure(Reason reason) implements PasswordAuthenticationAttempt {
        public Failure {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    enum Reason {
        INVALID_CREDENTIALS,
        PRINCIPAL_UNAVAILABLE,
        CREDENTIAL_LOCKED
    }
}
