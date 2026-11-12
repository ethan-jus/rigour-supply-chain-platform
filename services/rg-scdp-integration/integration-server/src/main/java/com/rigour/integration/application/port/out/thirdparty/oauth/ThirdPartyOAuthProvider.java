package com.rigour.integration.application.port.out.thirdparty.oauth;

import com.rigour.integration.domain.thirdparty.oauth.*;

import java.net.URI;
import java.util.UUID;

/** 公共授权不假设平台字段、签名或刷新方式相同。 */
public interface ThirdPartyOAuthProvider {
    String platformCode();

    String permissionPrefix();

    OAuthConfiguration configuration(UUID tenant, UUID connector);

    URI authorizationUri(OAuthConfiguration configuration);

    OAuthToken exchange(OAuthConfiguration configuration, String code);

    OAuthToken refresh(OAuthConfiguration configuration, OAuthToken previous);

    boolean verifySubject(OAuthConfiguration configuration, OAuthToken token);
}
