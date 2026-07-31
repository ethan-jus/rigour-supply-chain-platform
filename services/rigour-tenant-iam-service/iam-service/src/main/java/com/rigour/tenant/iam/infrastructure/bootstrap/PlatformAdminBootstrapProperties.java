package com.rigour.tenant.iam.infrastructure.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 首个平台管理员初始化参数；刻意不提供密码字段，避免Secret进入配置中心或进程参数。 */
@ConfigurationProperties(prefix = "rigour.iam.bootstrap.platform-admin")
public final class PlatformAdminBootstrapProperties {

    private boolean enabled;
    private String username;
    private String displayName;
    private int minimumPasswordLength = 14;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getMinimumPasswordLength() {
        return minimumPasswordLength;
    }

    public void setMinimumPasswordLength(int minimumPasswordLength) {
        this.minimumPasswordLength = minimumPasswordLength;
    }
}
