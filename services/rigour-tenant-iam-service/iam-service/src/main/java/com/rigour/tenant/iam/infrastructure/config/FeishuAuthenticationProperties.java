package com.rigour.tenant.iam.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 飞书H5免登配置；App Secret只能从进程Secret注入。 */
@ConfigurationProperties(prefix = "rigour.iam.feishu")
public final class FeishuAuthenticationProperties {

    private boolean enabled;
    private String appId = "";
    private String appSecret = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration tokenSafetyWindow = Duration.ofSeconds(60);
    private Duration sessionTimeToLive = Duration.ofHours(8);
    private Duration accessTokenTimeToLive = Duration.ofHours(1);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public Duration getTokenSafetyWindow() { return tokenSafetyWindow; }
    public void setTokenSafetyWindow(Duration tokenSafetyWindow) { this.tokenSafetyWindow = tokenSafetyWindow; }
    public Duration getSessionTimeToLive() { return sessionTimeToLive; }
    public void setSessionTimeToLive(Duration sessionTimeToLive) { this.sessionTimeToLive = sessionTimeToLive; }
    public Duration getAccessTokenTimeToLive() { return accessTokenTimeToLive; }
    public void setAccessTokenTimeToLive(Duration accessTokenTimeToLive) {
        this.accessTokenTimeToLive = accessTokenTimeToLive;
    }

    public void validate() {
        if (!enabled) throw new IllegalStateException("飞书H5免登未启用");
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("飞书 App ID 或 App Secret 未配置");
        }
        positive(connectTimeout, "connect-timeout", Duration.ofSeconds(30));
        positive(readTimeout, "read-timeout", Duration.ofSeconds(120));
        positive(tokenSafetyWindow, "token-safety-window", Duration.ofMinutes(30));
        positive(sessionTimeToLive, "session-time-to-live", Duration.ofDays(1));
        positive(accessTokenTimeToLive, "access-token-time-to-live", sessionTimeToLive);
    }

    private static void positive(Duration value, String field, Duration maximum) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException("飞书 " + field + " 配置无效");
        }
    }
}
