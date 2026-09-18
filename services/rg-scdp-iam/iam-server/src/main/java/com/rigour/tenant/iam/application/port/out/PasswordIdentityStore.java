package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.domain.model.credential.Credential;
import com.rigour.tenant.iam.domain.model.credential.PasswordIdentity;
import com.rigour.tenant.iam.domain.model.session.AuthSession;
import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 密码认证与统一会话的持久化端口；查找、计数更新和建会话必须处于同一事务。 */
public interface PasswordIdentityStore {

    Optional<PasswordIdentity> findForAuthentication(
            PrincipalScope principalScope, String tenantCode, String username);

    void recordFailure(
            PrincipalScope principalScope,
            UUID credentialId,
            Credential.FailureState failureState,
            long expectedVersion
    );

    void recordSuccess(
            PrincipalScope principalScope,
            UUID credentialId,
            String upgradedPasswordHash,
            Instant authenticatedAt,
            long expectedVersion
    );

    void createSession(AuthSession session);
}
