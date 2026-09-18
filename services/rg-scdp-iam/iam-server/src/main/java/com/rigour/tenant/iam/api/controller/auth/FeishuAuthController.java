package com.rigour.tenant.iam.api.controller.auth;

import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.tenant.iam.application.service.auth.FeishuAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 飞书H5免登HTTP入口；授权码、飞书访问令牌和App Secret均不得进入日志或响应。 */
@RestController
@RequestMapping("/api/v1/auth/feishu")
@ConditionalOnProperty(prefix = "rigour.iam.feishu", name = "enabled", havingValue = "true")
public final class FeishuAuthController {

    private static final Logger log = LoggerFactory.getLogger(FeishuAuthController.class);
    private final FeishuAuthenticationService service;

    public FeishuAuthController(FeishuAuthenticationService service) {
        this.service = service;
    }

    @PostMapping("/exchange")
    public ResponseEntity<ApiResponse<ExchangeView>> exchange(
            @Valid @RequestBody ExchangeCommand command, HttpServletRequest request) {
        log.info("飞书免登交换请求进入IAM requestId={}", RequestContext.getRequestId());
        var result = service.login(new FeishuAuthenticationService.LoginCommand(
                command.code(), "Feishu H5", sha256(request.getHeader("User-Agent")),
                remoteAddress(request.getRemoteAddr())));
        log.info("飞书免登交换成功 requestId={} tenantId={} userId={}",
                RequestContext.getRequestId(), result.tenantId(), result.userId());
        ExchangeView view = new ExchangeView(
                result.token(),
                new ExchangeUserView(result.userId().toString(), result.displayName(),
                        result.avatarUrl(), result.tenantId().toString()),
                result.feishuOpenId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(view));
    }

    public record ExchangeCommand(@NotBlank @Size(max = 2048) String code) { }

    public record ExchangeView(String token, ExchangeUserView user, String feishuOpenId) { }

    public record ExchangeUserView(String userId, String name, String avatar, String tenantId) { }

    private static byte[] sha256(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static byte[] remoteAddress(String value) {
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
