package com.rigour.tenant.iam.infrastructure.security.oidc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OIDC授权上下文运行配置。Base64密钥值只能来自部署Secret环境变量，Nacos仅保存变量引用。
 */
@ConfigurationProperties(prefix = "rigour.iam.oidc.authorization-attributes")
public final class OidcAuthorizationProperties {

    private boolean enabled;
    private String activeKeyVersion;
    private Map<String, String> keysBase64 = new LinkedHashMap<>();
    private boolean allowEphemeralLocalKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getActiveKeyVersion() {
        return activeKeyVersion;
    }

    public void setActiveKeyVersion(String activeKeyVersion) {
        this.activeKeyVersion = activeKeyVersion;
    }

    public Map<String, String> getKeysBase64() {
        return keysBase64;
    }

    public void setKeysBase64(Map<String, String> keysBase64) {
        this.keysBase64 = keysBase64 == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keysBase64);
    }

    public boolean isAllowEphemeralLocalKey() { return allowEphemeralLocalKey; }
    public void setAllowEphemeralLocalKey(boolean value) { allowEphemeralLocalKey = value; }

    public Map<String, byte[]> decodeKeys() {
        if (activeKeyVersion == null || activeKeyVersion.isBlank()) {
            throw new IllegalStateException("OIDC authorization attributes active key version is required");
        }
        if (keysBase64.isEmpty() && allowEphemeralLocalKey) {
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            return Map.of(activeKeyVersion, key);
        }
        if (keysBase64.isEmpty()) {
            throw new IllegalStateException("OIDC authorization attributes keys are required");
        }
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        keysBase64.forEach((version, encoded) -> {
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalStateException("OIDC authorization attributes key cannot be blank: " + version);
            }
            try {
                decoded.put(version, Base64.getDecoder().decode(encoded));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "OIDC authorization attributes key is not valid Base64: " + version, exception);
            }
        });
        return decoded;
    }
}
