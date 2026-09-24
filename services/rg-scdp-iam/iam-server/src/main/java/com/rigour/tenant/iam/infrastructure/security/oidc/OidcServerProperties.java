package com.rigour.tenant.iam.infrastructure.security.oidc;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authorization Server总开关与公开Issuer；HTTP来源例外必须显式配置。 */
@ConfigurationProperties(prefix = "rigour.iam.oidc.server")
public final class OidcServerProperties {

    /**
     * 仅在local开发模式动态放行私有IPv4网段的HTTP SCDP来源。
     * 这里不写死任何一台电脑的IP；Vite实际绑定的本机IP由操作系统和用户访问地址决定。
     */
    private static final List<String> PRIVATE_LAN_ORIGIN_PATTERNS = List.of(
            "http://10.*.*.*:[*]",
            "http://172.16.*.*:[*]",
            "http://172.17.*.*:[*]",
            "http://172.18.*.*:[*]",
            "http://172.19.*.*:[*]",
            "http://172.20.*.*:[*]",
            "http://172.21.*.*:[*]",
            "http://172.22.*.*:[*]",
            "http://172.23.*.*:[*]",
            "http://172.24.*.*:[*]",
            "http://172.25.*.*:[*]",
            "http://172.26.*.*:[*]",
            "http://172.27.*.*:[*]",
            "http://172.28.*.*:[*]",
            "http://172.29.*.*:[*]",
            "http://172.30.*.*:[*]",
            "http://172.31.*.*:[*]",
            "http://192.168.*.*:[*]"
    );

    private boolean enabled;
    private String issuer;
    private List<String> allowedOrigins = new ArrayList<>();
    private boolean allowInsecureLoopback;
    /** 仅供本机开发时通过局域网IP访问SCDP；生产配置必须保持false并使用HTTPS。 */
    private boolean allowInsecureLan;
    /** 临时HTTP部署只放行allowed-origins中的精确来源，不扩大Issuer或私网来源规则。 */
    private boolean allowInsecureHttpOrigins;

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
    public boolean isAllowInsecureLan() { return allowInsecureLan; }
    public void setAllowInsecureLan(boolean value) { this.allowInsecureLan = value; }
    public boolean isAllowInsecureHttpOrigins() { return allowInsecureHttpOrigins; }
    public void setAllowInsecureHttpOrigins(boolean value) { this.allowInsecureHttpOrigins = value; }

    public List<String> requireAllowedOrigins() {
        List<String> origins = allowedOrigins.stream().map(String::strip).peek(value -> {
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("OIDC allowed origin is invalid", exception);
            }
            boolean allowedScheme = isAllowedScheme(uri)
                    || allowInsecureHttpOrigins && "http".equalsIgnoreCase(uri.getScheme());
            if (!allowedScheme || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                throw new IllegalStateException("OIDC allowed origin must be an exact HTTPS origin or explicitly approved HTTP origin");
            }
        }).toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException("OIDC requires at least one allowed SCDP origin");
        }
        return origins;
    }

    /**
     * 返回CORS实际使用的来源规则。
     *
     * <p>默认只使用明确列出的HTTPS来源；临时HTTP部署的来源例外仍为精确匹配。
     * local开发打开allow-insecure-lan后，才额外加入RFC1918私网HTTP模式。</p>
     */
    public List<String> requireAllowedOriginPatterns() {
        LinkedHashSet<String> patterns = new LinkedHashSet<>(requireAllowedOrigins());
        if (allowInsecureLan) {
            patterns.addAll(PRIVATE_LAN_ORIGIN_PATTERNS);
        }
        return List.copyOf(patterns);
    }

    /** 登录请求没有可恢复的OIDC SavedRequest时，回到统一SCDP而不是IAM受保护根路径。 */
    public String requirePrimaryScdpEntryUri() {
        List<String> origins = requireAllowedOrigins();
        if (origins.isEmpty()) {
            throw new IllegalStateException("OIDC primary SCDP origin is required");
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
        if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (allowInsecureLoopback && isLoopbackHost(host)) return true;
        return allowInsecureLan && isPrivateIpv4Host(host);
    }

    private boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private boolean isPrivateIpv4Host(String host) {
        if (host == null) return false;
        String[] segments = host.split("\\.", -1);
        if (segments.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int index = 0; index < segments.length; index++) {
                if (segments[index].isEmpty()) return false;
                octets[index] = Integer.parseInt(segments[index]);
                if (octets[index] < 0 || octets[index] > 255) return false;
            }
        } catch (NumberFormatException exception) {
            return false;
        }
        return octets[0] == 10
                || octets[0] == 192 && octets[1] == 168
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31;
    }
}
