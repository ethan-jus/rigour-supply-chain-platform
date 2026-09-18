package com.rigour.tenant.iam.infrastructure.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅本地profile使用的RSA签名密钥初始化参数。 */
@ConfigurationProperties(prefix = "rigour.iam.bootstrap.local-signing-key")
public final class LocalSigningKeyBootstrapProperties {
    private boolean enabled;
    private String path = "tmp/iam-local-signing-key.pem";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getPath() { return path; }
    public void setPath(String value) { path = value; }
}
