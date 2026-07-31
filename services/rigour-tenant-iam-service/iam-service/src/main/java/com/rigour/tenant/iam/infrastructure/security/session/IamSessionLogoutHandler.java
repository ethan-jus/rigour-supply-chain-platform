package com.rigour.tenant.iam.infrastructure.security.session;

import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.transaction.support.TransactionOperations;

/** 浏览器退出时同步撤销IAM会话、授权与Refresh Token链。 */
public final class IamSessionLogoutHandler implements LogoutHandler {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;

    public IamSessionLogoutHandler(JdbcTemplate jdbcTemplate, TransactionOperations transactionOperations) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        UUID sessionId = sessionId(authentication);
        if (sessionId == null) {
            return;
        }
        transactionOperations.executeWithoutResult(ignored -> revoke(sessionId));
    }

    private void revoke(UUID sessionId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        byte[] encodedSessionId = UuidBinaryCodec.encode(sessionId);
        jdbcTemplate.update("""
                        UPDATE iam_auth_session
                           SET status = 'REVOKED', revoked_at = ?, revoke_reason = 'USER_LOGOUT', version = version + 1
                         WHERE id = ? AND status = 'ACTIVE'
                        """, now, encodedSessionId);
        jdbcTemplate.update("""
                        UPDATE iam_oauth_authorization
                           SET status = 'REVOKED', revoked_at = ?, revoke_reason = 'USER_LOGOUT',
                               version = version + 1, updated_at = ?
                         WHERE session_id = ? AND status <> 'REVOKED'
                        """, now, now, encodedSessionId);
        jdbcTemplate.update("""
                        UPDATE iam_refresh_token
                           SET revoked_at = COALESCE(revoked_at, ?), revoke_reason = 'USER_LOGOUT'
                         WHERE session_id = ?
                        """, now, encodedSessionId);
    }

    private static UUID sessionId(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (!(authentication.getDetails() instanceof Map<?, ?> details)) {
            return authentication.getPrincipal() instanceof Authentication principal
                    ? sessionId(principal) : null;
        }
        Object value = details.get(IamAuthenticationDetails.SESSION_ID);
        try {
            return value == null ? null : UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
