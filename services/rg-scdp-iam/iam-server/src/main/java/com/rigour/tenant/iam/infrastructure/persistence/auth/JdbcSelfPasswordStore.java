package com.rigour.tenant.iam.infrastructure.persistence.auth;

import static com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec.encode;

import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.application.port.out.SelfPasswordStore;
import com.rigour.tenant.iam.infrastructure.security.session.PasswordAuthenticationProperties;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcSelfPasswordStore implements SelfPasswordStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordHasher passwords;
    private final PasswordAuthenticationProperties policy;

    public JdbcSelfPasswordStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            PasswordHasher passwords, PasswordAuthenticationProperties policy) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.passwords = passwords;
        this.policy = policy;
    }

    @Override
    public void change(UUID tenantId, UUID userId, String currentPassword, String newPassword) {
        String error = tx.execute(status -> {
            var credentials = jdbc.query("""
                    SELECT c.password_hash,c.failed_attempts,c.locked_until
                    FROM iam_user_credential c
                    JOIN iam_user u ON u.tenant_id=c.tenant_id AND u.id=c.user_id
                    JOIN iam_tenant t ON t.id=u.tenant_id
                    WHERE c.tenant_id=? AND c.user_id=? AND c.credential_type='PASSWORD' AND c.status='ACTIVE'
                      AND u.status='ACTIVE' AND u.deleted_at IS NULL AND t.status='ACTIVE' AND t.deleted_at IS NULL
                    FOR UPDATE
                    """, (rs, n) -> new Credential(rs.getString(1), rs.getInt(2), rs.getObject(3, LocalDateTime.class)),
                    encode(tenantId), encode(userId));
            if (credentials.size() != 1) return "当前账号没有可修改的密码凭证";
            var credential = credentials.getFirst();
            var now = LocalDateTime.now(ZoneOffset.UTC);
            if (credential.lockedUntil() != null && credential.lockedUntil().isAfter(now))
                return "密码验证已暂时锁定，请稍后重试";
            if (!passwords.matches(currentPassword, credential.hash())) {
                int failures = (credential.lockedUntil() == null ? credential.failures() : 0) + 1;
                jdbc.update("""
                        UPDATE iam_user_credential SET failed_attempts=?,last_failed_at=?,locked_until=?,
                          version=version+1,updated_at=? WHERE tenant_id=? AND user_id=? AND credential_type='PASSWORD'
                        """, failures, now, failures >= policy.getMaximumFailedAttempts() ? now.plus(policy.getLockDuration()) : null,
                        now, encode(tenantId), encode(userId));
                return failures >= policy.getMaximumFailedAttempts() ? "原密码错误次数过多，请稍后重试" : "原密码不正确";
            }
            jdbc.update("""
                    UPDATE iam_user_credential SET password_hash=?,algorithm='ARGON2ID',algorithm_version=1,
                      failed_attempts=0,last_failed_at=NULL,locked_until=NULL,password_changed_at=?,updated_at=?,version=version+1
                    WHERE tenant_id=? AND user_id=? AND credential_type='PASSWORD' AND status='ACTIVE'
                    """, passwords.hash(newPassword), now, now, encode(tenantId), encode(userId));
            jdbc.update("""
                    UPDATE iam_user SET security_version=security_version+1,version=version+1,updated_at=?,updated_by=?
                    WHERE tenant_id=? AND id=?
                    """, now, encode(userId), encode(tenantId), encode(userId));
            // 同时撤销浏览器 SSO 和 OAuth 会话，防止旧 Cookie 再次换取 Token。
            jdbc.update("""
                    UPDATE iam_auth_session SET status='REVOKED',revoked_at=?,revoke_reason='PASSWORD_CHANGED',version=version+1
                    WHERE tenant_id=? AND principal_id=? AND principal_scope='TENANT' AND status='ACTIVE'
                    """, now, encode(tenantId), encode(userId));
            return null;
        });
        // 错误次数必须提交，不能因向页面返回验证错误而回滚。
        if (error != null) throw new RequestValidationException(error);
    }

    private record Credential(String hash, int failures, LocalDateTime lockedUntil) {
        @Override public String toString() { return "Credential[REDACTED]"; }
    }
}
