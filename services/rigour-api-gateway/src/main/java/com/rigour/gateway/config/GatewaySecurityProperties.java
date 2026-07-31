package com.rigour.gateway.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gateway资源服务器开关和IAM信任锚；默认关闭并失败关闭。 */
@ConfigurationProperties(prefix = "rigour.gateway.security")
public final class GatewaySecurityProperties {

    private boolean enabled;
    private String issuer;
    private String jwkSetUri;
    private List<String> audience = new ArrayList<>(List.of("rigour-api"));
    private boolean allowInsecureLoopback;
    private boolean currentTokenValidationEnabled;
    private String iamCurrentTokenUri;
    private Duration currentTokenConnectTimeout = Duration.ofSeconds(2);
    private Duration currentTokenReadTimeout = Duration.ofSeconds(3);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public List<String> getAudience() {
        return List.copyOf(audience);
    }

    public void setAudience(List<String> audience) {
        this.audience = audience == null ? new ArrayList<>() : new ArrayList<>(audience);
    }

    public boolean isAllowInsecureLoopback() { return allowInsecureLoopback; }
    public void setAllowInsecureLoopback(boolean value) { this.allowInsecureLoopback = value; }
    public boolean isCurrentTokenValidationEnabled() { return currentTokenValidationEnabled; }
    public void setCurrentTokenValidationEnabled(boolean value) { this.currentTokenValidationEnabled = value; }
    public String getIamCurrentTokenUri() { return iamCurrentTokenUri; }
    public void setIamCurrentTokenUri(String value) { this.iamCurrentTokenUri = value; }
    public Duration getCurrentTokenConnectTimeout() { return currentTokenConnectTimeout; }
    public void setCurrentTokenConnectTimeout(Duration value) { currentTokenConnectTimeout = value; }
    public Duration getCurrentTokenReadTimeout() { return currentTokenReadTimeout; }
    public void setCurrentTokenReadTimeout(Duration value) { currentTokenReadTimeout = value; }

    public String requireIssuer() {
        return requireUrl(issuer, "issuer", false);
    }

    public String requireJwkSetUri() {
        return requireUrl(jwkSetUri, "jwk-set-uri", true);
    }

    public String requireIamCurrentTokenUri() { return requireUrl(iamCurrentTokenUri, "iam-current-token-uri", true); }

    public void requireCurrentTokenValidation() {
        if (!currentTokenValidationEnabled) {
            throw new IllegalStateException("Gateway security requires IAM current-token validation");
        }
        requireIamCurrentTokenUri();
        requirePositiveTimeout(currentTokenConnectTimeout, "current-token-connect-timeout");
        requirePositiveTimeout(currentTokenReadTimeout, "current-token-read-timeout");
    }

    public List<String> requireAudience() {
        List<String> values = getAudience();
        if (values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("Gateway audience cannot be empty");
        }
        return values;
    }

    private String requireUrl(String raw, String field, boolean allowPath) {
        String value = raw == null ? "" : raw.strip();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Gateway " + field + " must be a valid URL", exception);
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "::1".equals(uri.getHost()));
        if (!(secure || loopback) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (!allowPath && uri.getPath() != null && !uri.getPath().isEmpty())
                || value.endsWith("/")) {
            throw new IllegalStateException("Gateway " + field + " must be HTTPS or approved loopback HTTP without a trailing slash");
        }
        return value;
    }

    private static void requirePositiveTimeout(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("Gateway " + field + " must be between 1ms and 30s");
        }
    }
}
