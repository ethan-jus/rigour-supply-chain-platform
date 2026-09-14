package com.rigour.collaboration.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 协作服务运行参数；密钥只允许从环境或安全配置注入。 */
@ConfigurationProperties(prefix = "collaboration")
public record CollaborationProperties(
        LiveKit livekit,
        int maxGroupMembers,
        int maxMessageTextLength,
        int maxAttachmentBytes
) {
    public CollaborationProperties {
        livekit = livekit == null ? new LiveKit("", "", "", Duration.ofMinutes(30)) : livekit;
        maxGroupMembers = maxGroupMembers <= 0 ? 500 : maxGroupMembers;
        maxMessageTextLength = maxMessageTextLength <= 0 ? 4000 : maxMessageTextLength;
        maxAttachmentBytes = maxAttachmentBytes <= 0 ? 50 * 1024 * 1024 : maxAttachmentBytes;
    }

    public record LiveKit(String url, String apiKey, String apiSecret, Duration tokenTtl) {
        public LiveKit {
            url = url == null ? "" : url.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            apiSecret = apiSecret == null ? "" : apiSecret.trim();
            tokenTtl = tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()
                    ? Duration.ofMinutes(30) : tokenTtl;
        }
    }
}
