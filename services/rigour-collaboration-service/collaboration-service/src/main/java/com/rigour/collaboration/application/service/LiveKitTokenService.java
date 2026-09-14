package com.rigour.collaboration.application.service;

import com.rigour.collaboration.infrastructure.config.CollaborationProperties;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 生成 LiveKit AccessToken；服务端只签发短期入会授权，不向 App 暴露 API Secret。 */
@Service
public class LiveKitTokenService {
    private final CollaborationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LiveKitTokenService(CollaborationProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Token issue(String roomName, String participantIdentity, String displayName) {
        CollaborationProperties.LiveKit liveKit = properties.livekit();
        if (liveKit.url().isBlank() || liveKit.apiKey().isBlank() || liveKit.apiSecret().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "LiveKit尚未配置", List.of());
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(liveKit.tokenTtl());
        Map<String, Object> claims = Map.of(
                "iss", liveKit.apiKey(),
                "sub", participantIdentity,
                "name", displayName,
                "nbf", now.getEpochSecond(),
                "exp", expiresAt.getEpochSecond(),
                "video", Map.of(
                        "roomJoin", true,
                        "room", roomName,
                        "canPublish", true,
                        "canSubscribe", true,
                        "canPublishData", true
                )
        );
        return new Token(liveKit.url(), sign(claims, liveKit.apiSecret()), expiresAt);
    }

    private String sign(Map<String, Object> claims, String secret) {
        try {
            String header = encodeJson(Map.of("alg", "HS256", "typ", "JWT"));
            String payload = encodeJson(claims);
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "LiveKit Token签发失败", List.of());
        }
    }

    private String encodeJson(Object value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(value));
    }

    public record Token(String liveKitUrl, String token, Instant expiresAt) {
    }
}
