package com.rigour.integration.application.port.out;

import com.rigour.integration.api.v1.model.HuolalaModels.Connection;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/** 只读开放平台适配器。OAuth令牌仅在服务端凭据端口内流转。 */
public interface HuolalaGateway {
    record Token(String accessToken, String refreshToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "Token[REDACTED]";
        }
    }

    boolean ready(Connection connection);

    String authorizeUrl(Connection connection);

    Token exchange(Connection connection, String code);

    Token refresh(Connection connection, Token previous);

    JsonNode read(
            Connection connection, Token token, String method, Map<String, Object> parameters);

    Token load(String tenant, String connector);

    void save(String tenant, String connector, Token token);
}
