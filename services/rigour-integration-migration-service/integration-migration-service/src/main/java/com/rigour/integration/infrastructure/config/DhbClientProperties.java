package com.rigour.integration.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 订货宝外呼策略；供应商没有公布的值必须在合同确认后覆盖，不从代码猜测。 */
@ConfigurationProperties(prefix = "rigour.integration.dhb.client")
public final class DhbClientProperties {

    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(15);
    /** 订货宝相对商品图片地址的独立源站；不能复用 ERP API base_url。 */
    private String imageBaseUrl = "https://img.dhb168.com/";
    /** 订货宝后台页面接口地址；只用于官方 API 暂缺的只读补全来源。 */
    private String adminBaseUrl = "https://m.dhb168.com/";
    /** 是否启用订货宝后台调拨主单只读补全。 */
    private boolean adminTransferEnabled = false;
    private int maxAttempts = 3;
    private Duration initialBackoff = Duration.ofMillis(200);
    private Duration maxBackoff = Duration.ofSeconds(3);
    private int requestsPerSecond = 5;
    private int rateLimitBurst = 5;
    private Duration tokenSafetyWindow = Duration.ofSeconds(60);

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public String getImageBaseUrl() { return imageBaseUrl; }
    public void setImageBaseUrl(String value) { imageBaseUrl = value; }
    public String getAdminBaseUrl() { return adminBaseUrl; }
    public void setAdminBaseUrl(String value) { adminBaseUrl = value; }
    public boolean isAdminTransferEnabled() { return adminTransferEnabled; }
    public void setAdminTransferEnabled(boolean value) { adminTransferEnabled = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration value) { initialBackoff = value; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration value) { maxBackoff = value; }
    public int getRequestsPerSecond() { return requestsPerSecond; }
    public void setRequestsPerSecond(int value) { requestsPerSecond = value; }
    public int getRateLimitBurst() { return rateLimitBurst; }
    public void setRateLimitBurst(int value) { rateLimitBurst = value; }
    public Duration getTokenSafetyWindow() { return tokenSafetyWindow; }
    public void setTokenSafetyWindow(Duration value) { tokenSafetyWindow = value; }

    public void validate() {
        positive(connectTimeout, "connect-timeout", 30);
        positive(readTimeout, "read-timeout", 120);
        validateImageBaseUrl(imageBaseUrl);
        validateUrl(adminBaseUrl, "admin-base-url");
        if (maxAttempts < 1 || maxAttempts > 8) {
            throw new IllegalStateException("订货宝 max-attempts 必须在1到8之间");
        }
        positive(initialBackoff, "initial-backoff", 30);
        positive(maxBackoff, "max-backoff", 120);
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalStateException("订货宝 max-backoff 不能小于 initial-backoff");
        }
        if (requestsPerSecond < 1 || requestsPerSecond > 1000) {
            throw new IllegalStateException("订货宝 requests-per-second 必须在1到1000之间");
        }
        if (rateLimitBurst < 1 || rateLimitBurst > 1000) {
            throw new IllegalStateException("订货宝 rate-limit-burst 必须在1到1000之间");
        }
        positive(tokenSafetyWindow, "token-safety-window", 3600);
    }

    private static void positive(Duration value, String name, int maxSeconds) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofSeconds(maxSeconds)) > 0) {
            throw new IllegalStateException("订货宝 " + name + " 必须在1ms到" + maxSeconds + "s之间");
        }
    }

    private static void validateImageBaseUrl(String value) {
        validateUrl(value, "image-base-url");
    }

    private static void validateUrl(String value, String name) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("订货宝 " + name + " 必须是无查询参数的 HTTP(S) 地址");
        }
    }
}
