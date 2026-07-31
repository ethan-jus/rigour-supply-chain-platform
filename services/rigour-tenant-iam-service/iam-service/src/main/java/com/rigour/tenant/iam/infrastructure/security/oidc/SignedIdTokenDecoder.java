package com.rigour.tenant.iam.infrastructure.security.oidc;

import org.springframework.security.oauth2.jwt.Jwt;

/** OIDC退出专用验签端口；允许规范要求可接受的过期ID Token，但仍校验签名和issuer。 */
@FunctionalInterface
public interface SignedIdTokenDecoder {
    Jwt decode(String token);
}
