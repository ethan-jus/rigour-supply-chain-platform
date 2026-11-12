package com.rigour.integration.domain.thirdparty.oauth;

import java.net.URI;
import java.util.UUID;

/** 服务端确认的连接身份；不包含APP_SECRET，也不从回调参数恢复租户。 */
public record OAuthConfiguration(
        UUID tenantId,
        String platformCode,
        UUID connectorId,
        String appKey,
        URI redirectUri,
        String fingerprint) {}
