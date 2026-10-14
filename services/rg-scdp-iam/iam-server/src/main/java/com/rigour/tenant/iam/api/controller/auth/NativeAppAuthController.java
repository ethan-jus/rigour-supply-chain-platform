package com.rigour.tenant.iam.api.controller.auth;

import com.rigour.shared.core.api.ApiResponse;
import com.rigour.tenant.iam.application.service.auth.NativeAppAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/native-app")
@ConditionalOnProperty(name = {"rigour.iam.authentication.native-app.enabled", "rigour.iam.oidc.server.enabled"}, havingValue = "true")
public final class NativeAppAuthController {
    private final NativeAppAuthentication authentication;
    public NativeAppAuthController(NativeAppAuthentication authentication) { this.authentication = authentication; }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<NativeAppAuthentication.LoginResult>> login(
            @RequestBody LoginRequest command, HttpServletRequest request) {
        try {
            var result = authentication.login(new NativeAppAuthentication.LoginCommand(
                    command.clientId(), command.redirectUri(), command.codeChallenge(), command.codeChallengeMethod(),
                    command.state(), command.username(), command.password(), command.tenantCode(), command.deviceName(),
                    sha256(request.getHeader("User-Agent")), remoteAddress(request.getRemoteAddr())));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(result));
        } finally {
            if (command.password() != null) Arrays.fill(command.password(), '\0');
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        authentication.logout(UUID.fromString(jwt.getClaimAsString("sessionId")),
                UUID.fromString(jwt.getClaimAsString("userId")), UUID.fromString(jwt.getClaimAsString("tenantId")));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(NativeAppAuthentication.Failure.class)
    public ResponseEntity<ApiResponse<Void>> failure(NativeAppAuthentication.Failure failure) {
        int status = switch (failure.kind()) {
            case INVALID_CLIENT, INVALID_REQUEST -> 400;
            case AUTHENTICATION_FAILED -> 401;
            case FORBIDDEN -> 403;
            case UNAVAILABLE -> 503;
        };
        String code = switch (failure.kind()) {
            case INVALID_CLIENT -> "IAM_NATIVE_CLIENT_INVALID";
            case INVALID_REQUEST -> "IAM_NATIVE_LOGIN_INVALID";
            case AUTHENTICATION_FAILED -> "IAM_LOGIN_FAILED";
            case FORBIDDEN -> "IAM_FORBIDDEN";
            case UNAVAILABLE -> "IAM_SESSION_CHECK_UNAVAILABLE";
        };
        String message = switch (failure.kind()) {
            case INVALID_CLIENT -> "当前 App 登录配置不可用，请联系管理员";
            case INVALID_REQUEST -> "登录参数无效，请重新输入";
            case AUTHENTICATION_FAILED -> "账号或密码错误，或账号暂不可用";
            case FORBIDDEN -> "不能退出其他类型的登录会话";
            case UNAVAILABLE -> "登录服务暂不可用，请稍后重试";
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiResponse.error(code, message, List.of()));
    }

    public record LoginRequest(String clientId, String redirectUri, String codeChallenge,
            String codeChallengeMethod, String state, String username, char[] password, String tenantCode, String deviceName) {
        @Override public String toString() { return "NativeAppLoginRequest[redacted]"; }
    }

    private static byte[] sha256(String value) {
        if (value == null || value.isBlank()) return null;
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static byte[] remoteAddress(String value) {
        try { return InetAddress.getByName(value).getAddress(); }
        catch (UnknownHostException exception) { return null; }
    }
}
