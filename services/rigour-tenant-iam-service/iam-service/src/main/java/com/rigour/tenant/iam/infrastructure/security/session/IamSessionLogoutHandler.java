package com.rigour.tenant.iam.infrastructure.security.session;

import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.transaction.support.TransactionOperations;

/** 浏览器退出时同步撤销IAM会话、授权与Refresh Token链。 */
public final class IamSessionLogoutHandler implements LogoutHandler {

    private static final Logger log = LoggerFactory.getLogger(IamSessionLogoutHandler.class);

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
            log.warn("IAM退出未解析到会话，未执行数据库撤销");
            return;
        }
        transactionOperations.executeWithoutResult(ignored -> revoke(sessionId));
        log.info("IAM退出完成，会话已撤销 sessionId={}", sessionId);
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
        if (authentication instanceof OidcLogoutAuthenticationToken oidcLogout) {
            UUID hintedSessionId = parseUuid(oidcLogout.getSessionId());
            if (hintedSessionId != null) {
                return hintedSessionId;
            }
            return oidcLogout.getPrincipal() instanceof Authentication principal
                    ? sessionId(principal) : null;
        }
        if (!(authentication.getDetails() instanceof Map<?, ?> details)) {
            return authentication.getPrincipal() instanceof Authentication principal
                    ? sessionId(principal) : null;
        }
        return parseUuid(details.get(IamAuthenticationDetails.SESSION_ID));
    }

    private static UUID parseUuid(Object value) {
        try {
            return value == null ? null : UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
