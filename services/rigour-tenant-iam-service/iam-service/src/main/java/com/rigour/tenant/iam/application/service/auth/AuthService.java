package com.rigour.tenant.iam.application.service.auth;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.application.port.out.PasswordIdentityStore;
import com.rigour.tenant.iam.domain.model.credential.Credential;
import com.rigour.tenant.iam.domain.model.credential.PasswordIdentity;
import com.rigour.tenant.iam.domain.model.session.AuthSession;
import java.nio.CharBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/** 密码登录用例；调用方负责在事务中执行，以原子提交失败计数或登录会话。 */
public final class AuthService {

    private final PasswordIdentityStore identityStore;
    private final PasswordHasher passwordHasher;
    private final IdentifierGenerator identifierGenerator;
    private final Clock clock;
    private final PasswordAuthenticationPolicy policy;

    public AuthService(
            PasswordIdentityStore identityStore,
            PasswordHasher passwordHasher,
            IdentifierGenerator identifierGenerator,
            Clock clock,
            PasswordAuthenticationPolicy policy
    ) {
        this.identityStore = identityStore;
        this.passwordHasher = passwordHasher;
        this.identifierGenerator = identifierGenerator;
        this.clock = clock;
        this.policy = policy;
    }

    public PasswordAuthenticationAttempt authenticate(PasswordLoginCommand command) {
        char[] rawPassword = command.password();
        try {
            Optional<PasswordIdentity> found = identityStore.findForAuthentication(
                    command.principalScope(), command.tenantCode(), command.username());
            if (found.isEmpty()) {
                passwordHasher.consumeDummyVerification(CharBuffer.wrap(rawPassword));
                return failure(PasswordAuthenticationAttempt.Reason.INVALID_CREDENTIALS);
            }

            PasswordIdentity identity = found.orElseThrow();
            Credential credential = identity.credential();
            boolean passwordMatches;
            try {
                passwordMatches = passwordHasher.matches(CharBuffer.wrap(rawPassword), credential.passwordHash());
            } catch (RuntimeException malformedHash) {
                return failure(PasswordAuthenticationAttempt.Reason.INVALID_CREDENTIALS);
            }

            Instant now = clock.instant();
            if (!identity.isActive()) {
                return failure(PasswordAuthenticationAttempt.Reason.PRINCIPAL_UNAVAILABLE);
            }
            if (!credential.isAvailableAt(now)) {
                return failure(PasswordAuthenticationAttempt.Reason.CREDENTIAL_LOCKED);
            }
            if (!passwordMatches) {
                Credential.FailureState failureState = credential.nextFailure(
                        now, policy.maximumFailedAttempts(), policy.lockDuration());
                identityStore.recordFailure(
                        identity.principalScope(), credential.id(), failureState, credential.version());
                return failure(PasswordAuthenticationAttempt.Reason.INVALID_CREDENTIALS);
            }

            String upgradedHash = passwordHasher.needsUpgrade(credential.passwordHash())
                    ? passwordHasher.hash(CharBuffer.wrap(rawPassword))
                    : null;
            identityStore.recordSuccess(
                    identity.principalScope(), credential.id(), upgradedHash, now, credential.version());

            AuthSession session = new AuthSession(
                    identifierGenerator.nextId(),
                    identity.principalScope(),
                    identity.tenantId(),
                    identity.principalId(),
                    command.clientType(),
                    command.deviceName(),
                    command.clientFingerprintHash(),
                    command.userAgentHash(),
                    command.ipAddress(),
                    now,
                    now,
                    now.plus(policy.sessionTimeToLive())
            );
            identityStore.createSession(session);
            return new PasswordAuthenticationAttempt.Success(
                    session.id(), identity.principalScope(), identity.tenantId(), identity.principalId(),
                    identity.displayName(), identity.platformRole(), identity.securityVersion());
        } finally {
            Arrays.fill(rawPassword, '\0');
            command.erasePassword();
        }
    }

    private static PasswordAuthenticationAttempt.Failure failure(PasswordAuthenticationAttempt.Reason reason) {
        return new PasswordAuthenticationAttempt.Failure(reason);
    }
}
