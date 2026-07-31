package com.rigour.tenant.iam.infrastructure.security.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 密码登录运行参数；默认值仅是一期DEV基线，生产前必须经压测和安全评审。 */
@ConfigurationProperties(prefix = "rigour.iam.authentication.password")
public final class PasswordAuthenticationProperties {

    private int maximumFailedAttempts = 5;
    private Duration lockDuration = Duration.ofMinutes(15);
    private Duration sessionTimeToLive = Duration.ofHours(8);

    public int getMaximumFailedAttempts() {
        return maximumFailedAttempts;
    }

    public void setMaximumFailedAttempts(int maximumFailedAttempts) {
        this.maximumFailedAttempts = maximumFailedAttempts;
    }

    public Duration getLockDuration() {
        return lockDuration;
    }

    public void setLockDuration(Duration lockDuration) {
        this.lockDuration = lockDuration;
    }

    public Duration getSessionTimeToLive() {
        return sessionTimeToLive;
    }

    public void setSessionTimeToLive(Duration sessionTimeToLive) {
        this.sessionTimeToLive = sessionTimeToLive;
    }
}
