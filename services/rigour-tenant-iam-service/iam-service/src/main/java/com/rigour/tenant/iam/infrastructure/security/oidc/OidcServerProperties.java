package com.rigour.tenant.iam.infrastructure.security.oidc;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authorization Server总开关与公开Issuer；仅显式本地模式允许loopback HTTP。 */
@ConfigurationProperties(prefix = "rigour.iam.oidc.server")
public final class OidcServerProperties {

    private boolean enabled;
    private String issuer;
    private List<String> allowedOrigins = new ArrayList<>();
    private boolean allowInsecureLoopback;

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

    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }

    public boolean isAllowInsecureLoopback() { return allowInsecureLoopback; }
    public void setAllowInsecureLoopback(boolean value) { this.allowInsecureLoopback = value; }

    public List<String> requireAllowedOrigins() {
        List<String> origins = allowedOrigins.stream().map(String::strip).peek(value -> {
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("OIDC allowed origin is invalid", exception);
            }
            if (!isAllowedScheme(uri) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                throw new IllegalStateException("OIDC allowed origin must be an exact HTTPS origin or approved loopback HTTP origin");
            }
        }).toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException("OIDC requires at least one allowed Portal origin");
        }
        return origins;
    }

    /** 登录请求没有可恢复的OIDC SavedRequest时，回到统一Portal而不是IAM受保护根路径。 */
    public String requirePrimaryPortalEntryUri() {
        List<String> origins = requireAllowedOrigins();
        if (origins.isEmpty()) {
            throw new IllegalStateException("OIDC primary Portal origin is required");
        }
        return origins.getFirst() + "/";
    }

    public String requireIssuer() {
        String value = issuer == null ? null : issuer.strip();
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("OIDC issuer is invalid", exception);
        }
        if (!isAllowedScheme(uri) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || value.endsWith("/")) {
            throw new IllegalStateException("OIDC issuer must be HTTPS or an approved loopback HTTP URL without a trailing slash");
        }
        return value;
    }

    private boolean isAllowedScheme(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) return true;
        if (!allowInsecureLoopback || !"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
