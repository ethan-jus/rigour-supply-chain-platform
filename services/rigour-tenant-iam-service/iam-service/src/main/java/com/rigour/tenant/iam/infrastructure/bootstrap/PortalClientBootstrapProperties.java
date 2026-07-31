package com.rigour.tenant.iam.infrastructure.bootstrap;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 公开Portal PKCE客户端的一次性初始化参数；默认关闭且不包含客户端Secret。 */
@ConfigurationProperties(prefix = "rigour.iam.bootstrap.portal-client")
public final class PortalClientBootstrapProperties {
    private boolean enabled;
    private String clientId = "rigour-portal-browser";
    private String clientName = "Rigour Portal Browser";
    private String redirectUri;
    private String postLogoutRedirectUri;
    private boolean allowInsecureLoopback;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getPostLogoutRedirectUri() { return postLogoutRedirectUri; }
    public void setPostLogoutRedirectUri(String value) { this.postLogoutRedirectUri = value; }
    public boolean isAllowInsecureLoopback() { return allowInsecureLoopback; }
    public void setAllowInsecureLoopback(boolean value) { this.allowInsecureLoopback = value; }

    public void validate() {
        if (clientId == null || clientId.isBlank() || clientName == null || clientName.isBlank()) {
            throw new IllegalStateException("Portal OAuth client id and name cannot be empty");
        }
        requireUri(redirectUri, "redirect-uri", false);
        requireUri(postLogoutRedirectUri, "post-logout-redirect-uri", true);
    }

    private void requireUri(String value, String field, boolean allowRoot) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Portal " + field + " is invalid", exception);
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "::1".equals(uri.getHost()));
        if (!(secure || loopback) || uri.getHost() == null
                || uri.getFragment() != null || uri.getUserInfo() != null
                || !allowRoot && (uri.getPath() == null || uri.getPath().equals("/"))) {
            throw new IllegalStateException("Portal " + field + " must be exact HTTPS or approved loopback HTTP URI");
        }
    }
}
