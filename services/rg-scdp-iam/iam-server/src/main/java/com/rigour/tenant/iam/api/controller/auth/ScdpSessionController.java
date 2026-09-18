package com.rigour.tenant.iam.api.controller.auth;

import com.rigour.tenant.iam.infrastructure.security.oidc.IamTokenClaimsResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Web 同源登录表单的会话状态与 CSRF 凭据；不签发业务 Token。 */
@RestController
@ConditionalOnProperty(prefix = "rigour.iam.oidc.server", name = "enabled", havingValue = "true")
public final class ScdpSessionController {
    private final IamTokenClaimsResolver claimsResolver;

    public ScdpSessionController(JdbcTemplate jdbcTemplate) {
        this.claimsResolver = new IamTokenClaimsResolver(jdbcTemplate);
    }

    @GetMapping("/scdp/session")
    public ResponseEntity<BrowserSession> session(Authentication authentication, CsrfToken csrf) {
        boolean authenticated;
        try {
            // 已撤销的会话、禁用的用户或租户不能触发前端自动恢复登录。
            claimsResolver.resolve(authentication);
            authenticated = true;
        } catch (IllegalStateException exception) {
            authenticated = false;
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new BrowserSession(authenticated, csrf.getParameterName(), csrf.getToken()));
    }

    public record BrowserSession(boolean authenticated, String csrfParameter, String csrfToken) {}
}
