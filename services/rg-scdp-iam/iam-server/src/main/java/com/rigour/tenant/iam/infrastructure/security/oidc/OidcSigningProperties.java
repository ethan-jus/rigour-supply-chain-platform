package com.rigour.tenant.iam.infrastructure.security.oidc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OIDC签名密钥开关；关闭时不加载数据库密钥，也不发布JWKS。 */
@ConfigurationProperties(prefix = "rigour.iam.oidc.signing")
public final class OidcSigningProperties {

    private boolean enabled;
    private int minimumRsaBits = 3072;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinimumRsaBits() {
        return minimumRsaBits;
    }

    public void setMinimumRsaBits(int minimumRsaBits) {
        this.minimumRsaBits = minimumRsaBits;
    }
}
