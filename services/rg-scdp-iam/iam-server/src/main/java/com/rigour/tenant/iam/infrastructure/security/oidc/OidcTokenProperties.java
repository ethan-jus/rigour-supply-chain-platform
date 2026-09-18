package com.rigour.tenant.iam.infrastructure.security.oidc;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 自包含Access Token的受众配置；ID Token受众仍由OIDC客户端ID决定。 */
@ConfigurationProperties(prefix = "rigour.iam.oidc.token")
public final class OidcTokenProperties {

    private List<String> accessTokenAudience = new ArrayList<>(List.of("rigour-api"));

    public List<String> getAccessTokenAudience() {
        return List.copyOf(accessTokenAudience);
    }

    public void setAccessTokenAudience(List<String> accessTokenAudience) {
        this.accessTokenAudience = accessTokenAudience == null
                ? new ArrayList<>() : new ArrayList<>(accessTokenAudience);
    }
}
