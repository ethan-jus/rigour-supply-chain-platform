package com.rigour.shared.context;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gateway到领域服务的可信上下文签名参数；密钥只能由环境Secret注入。 */
@ConfigurationProperties(prefix = "rigour.context.trust")
public final class ContextTrustProperties {
    private String activeKeyId = "v1";
    private Map<String, String> keysBase64 = new LinkedHashMap<>();
    private Duration maximumAge = Duration.ofSeconds(30);
    private int maximumRoles = 64;
    private int maximumPermissions = 512;
    private int maximumHeaderBytes = 16 * 1024;

    public String getActiveKeyId() { return activeKeyId; }
    public void setActiveKeyId(String value) { activeKeyId = value; }
    public Map<String, String> getKeysBase64() { return keysBase64; }
    public void setKeysBase64(Map<String, String> value) {
        keysBase64 = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public Duration getMaximumAge() { return maximumAge; }
    public void setMaximumAge(Duration value) { maximumAge = value; }
    public int getMaximumRoles() { return maximumRoles; }
    public void setMaximumRoles(int value) { maximumRoles = value; }
    public int getMaximumPermissions() { return maximumPermissions; }
    public void setMaximumPermissions(int value) { maximumPermissions = value; }
    public int getMaximumHeaderBytes() { return maximumHeaderBytes; }
    public void setMaximumHeaderBytes(int value) { maximumHeaderBytes = value; }

    byte[] requireActiveKey() { return requireKey(required(activeKeyId, "active key id")); }

    byte[] requireKey(String keyId) {
        validateLimits();
        String encoded = keysBase64.get(keyId);
        byte[] key;
        try { key = Base64.getDecoder().decode(required(encoded, "context trust key")); }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Context trust key must be valid Base64", exception);
        }
        if (key.length < 32) throw new IllegalStateException("Context trust key must contain at least 32 bytes");
        return key;
    }

    private void validateLimits() {
        if (maximumAge == null || maximumAge.isNegative() || maximumAge.isZero()
                || maximumAge.compareTo(Duration.ofMinutes(5)) > 0
                || maximumRoles < 1 || maximumRoles > 256
                || maximumPermissions < 1 || maximumPermissions > 2048
                || maximumHeaderBytes < 1024 || maximumHeaderBytes > 65536) {
            throw new IllegalStateException("Context trust limits are invalid");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalStateException(field + " is required");
        return value.strip();
    }
}
