package com.rigour.tenant.iam.api.controller.auth;

import com.rigour.tenant.iam.application.service.auth.SelfPasswordService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
public final class SelfPasswordController {
    private final SelfPasswordService service;

    public SelfPasswordController(SelfPasswordService service) { this.service = service; }

    @PostMapping("/api/v1/scdp/password")
    public ResponseEntity<Void> change(JwtAuthenticationToken authentication, @RequestBody PasswordCommand command) {
        if (authentication == null || !authentication.isAuthenticated()) throw new AccessDeniedException("需要有效登录");
        var token = authentication.getToken();
        String tenant = token.getClaimAsString("tenantId");
        // 不接受客户端传入账号 ID；只能修改已验证登录主体自己的密码。
        service.change(token.getClaimAsString("principalScope"), tenant == null ? null : UUID.fromString(tenant),
                UUID.fromString(token.getClaimAsString("principalId")), command.currentPassword(), command.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record PasswordCommand(String currentPassword, String newPassword) {
        @Override public String toString() { return "PasswordCommand[REDACTED]"; }
    }
}
