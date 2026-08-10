package com.rigour.integration.infrastructure.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书外部集成配置。
 *
 * <p>App Secret 只能通过进程环境或本地运行配置注入，不能写入 Git、Nacos 或日志。
 * 这里不在应用启动时校验 Secret，避免没有启用飞书链路的测试环境无法启动；真正签名时
 * 才进行失败关闭校验。</p>
 */
@ConfigurationProperties(prefix = "rigour.integration.feishu")
public final class FeishuClientProperties {

    private String appId = "";
    private String appSecret = "";
    private String allowedOrigins = "";
    /** 仅本机local调试时动态允许RFC1918私网HTTP来源；生产必须关闭。 */
    private boolean allowInsecureLan;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration tokenSafetyWindow = Duration.ofSeconds(60);

    public String getAppId() { return appId; }
    public void setAppId(String value) { appId = value; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String value) { appSecret = value; }
    public String getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(String value) { allowedOrigins = value; }
    public boolean isAllowInsecureLan() { return allowInsecureLan; }
    public void setAllowInsecureLan(boolean value) { allowInsecureLan = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public Duration getTokenSafetyWindow() { return tokenSafetyWindow; }
    public void setTokenSafetyWindow(Duration value) { tokenSafetyWindow = value; }

    public List<String> allowedOriginValues() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    public void validateForSigning() {
        if (isBlank(appId) || isBlank(appSecret)) {
            throw new IllegalStateException("飞书 App ID 或 App Secret 未配置");
        }
        if (allowedOriginValues().isEmpty()) {
            throw new IllegalStateException("飞书 JSSDK allowed-origins 未配置");
        }
        positive(connectTimeout, "connect-timeout", 30);
        positive(readTimeout, "read-timeout", 120);
        positive(tokenSafetyWindow, "token-safety-window", 3600);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void positive(Duration value, String name, int maxSeconds) {
        if (Objects.isNull(value) || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofSeconds(maxSeconds)) > 0) {
            throw new IllegalStateException("飞书 " + name + " 必须在1ms到" + maxSeconds + "s之间");
        }
    }
}
