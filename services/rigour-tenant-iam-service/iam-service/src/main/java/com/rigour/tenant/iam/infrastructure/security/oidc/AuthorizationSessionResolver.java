package com.rigour.tenant.iam.infrastructure.security.oidc;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

import java.util.UUID;

/** 将已认证主体的IAM会话绑定到OAuth授权；登录实现必须提供，禁止授权脱离会话独立存在。 */
@FunctionalInterface
public interface AuthorizationSessionResolver {

    UUID resolveSessionId(OAuth2Authorization authorization);
}
