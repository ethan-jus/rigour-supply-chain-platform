package com.rigour.integration.application.port.out.thirdparty.huolala;

import com.rigour.integration.api.v1.model.HuolalaModels.Connection;
import com.rigour.integration.domain.thirdparty.oauth.OAuthToken;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/** 只读开放平台适配器。OAuth令牌仅在服务端凭据端口内流转。 */
public interface HuolalaGateway {
    boolean ready(Connection connection);

    String authorizeUrl(Connection connection);

    OAuthToken exchange(Connection connection, String code);

    OAuthToken refresh(Connection connection, OAuthToken previous);

    JsonNode read(
            Connection connection, OAuthToken token, String method, Map<String, Object> parameters);

    String appKey(Connection connection);
}
