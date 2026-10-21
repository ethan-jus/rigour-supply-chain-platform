package com.rigour.tenant.iam.application.service.auth;

import java.util.UUID;

/** 公司原生客户端的登录适配；令牌签发仍由现有 OAuth 授权码流程负责。 */
public interface NativeAppAuthentication {
    LoginResult login(LoginCommand command);

    void logout(UUID sessionId, UUID userId, UUID tenantId);

    record LoginCommand(String clientId, String redirectUri, String codeChallenge,
            String codeChallengeMethod, String state, String username, char[] password,
            String tenantCode, String deviceName, byte[] userAgentHash, byte[] ipAddress) {
        @Override
        public String toString() { return "NativeAppLoginCommand[redacted]"; }
    }

    record LoginResult(String authorizationCode, long expiresIn, String state) {}

    final class Failure extends RuntimeException {
        private final Kind kind;
        public Failure(Kind kind) { super(kind.name()); this.kind = kind; }
        public Kind kind() { return kind; }
    }

    enum Kind { INVALID_REQUEST, INVALID_CLIENT, AUTHENTICATION_FAILED, UNAVAILABLE, FORBIDDEN }
}
