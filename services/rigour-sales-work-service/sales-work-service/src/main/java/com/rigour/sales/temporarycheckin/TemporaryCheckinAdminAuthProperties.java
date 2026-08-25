package com.rigour.sales.temporarycheckin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 临时打卡后台应用级认证参数；所有 Secret 只能从部署环境注入。 */
@Component
@ConfigurationProperties(prefix = "rigour.sales.temporary-checkin.admin-auth")
public class TemporaryCheckinAdminAuthProperties {

    private String cookieName = "__Host-rigour-sales-checkin-admin";
    private Duration sessionDuration = Duration.ofHours(8);
    private Duration idleTimeout = Duration.ofMinutes(30);
    private Duration temporaryPasswordTtl = Duration.ofHours(24);
    private Duration loginLockDuration = Duration.ofMinutes(15);
    private Duration sessionTouchInterval = Duration.ofMinutes(5);
    private int loginFailureThreshold = 5;
    private int pbkdf2Iterations = 310_000;
    private String bootstrapSecret;
    private String bootstrapProxyMarker;
    private List<String> bootstrapAllowedRemoteCidrs = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));

    public String getCookieName() { return cookieName; }
    public void setCookieName(String value) { cookieName = value; }
    public Duration getSessionDuration() { return sessionDuration; }
    public void setSessionDuration(Duration value) { sessionDuration = value; }
    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration value) { idleTimeout = value; }
    public Duration getTemporaryPasswordTtl() { return temporaryPasswordTtl; }
    public void setTemporaryPasswordTtl(Duration value) { temporaryPasswordTtl = value; }
    public Duration getLoginLockDuration() { return loginLockDuration; }
    public void setLoginLockDuration(Duration value) { loginLockDuration = value; }
    public Duration getSessionTouchInterval() { return sessionTouchInterval; }
    public void setSessionTouchInterval(Duration value) { sessionTouchInterval = value; }
    public int getLoginFailureThreshold() { return loginFailureThreshold; }
    public void setLoginFailureThreshold(int value) { loginFailureThreshold = value; }
    public int getPbkdf2Iterations() { return pbkdf2Iterations; }
    public void setPbkdf2Iterations(int value) { pbkdf2Iterations = value; }
    public String getBootstrapSecret() { return bootstrapSecret; }
    public void setBootstrapSecret(String value) { bootstrapSecret = value; }
    public String getBootstrapProxyMarker() { return bootstrapProxyMarker; }
    public void setBootstrapProxyMarker(String value) { bootstrapProxyMarker = value; }
    public List<String> getBootstrapAllowedRemoteCidrs() { return List.copyOf(bootstrapAllowedRemoteCidrs); }
    public void setBootstrapAllowedRemoteCidrs(List<String> value) {
        bootstrapAllowedRemoteCidrs = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
