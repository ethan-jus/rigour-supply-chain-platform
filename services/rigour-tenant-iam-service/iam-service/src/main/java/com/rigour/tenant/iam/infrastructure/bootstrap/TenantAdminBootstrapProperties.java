package com.rigour.tenant.iam.infrastructure.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 首个租户系统管理员初始化参数；密码只能由交互控制台读取。 */
@ConfigurationProperties(prefix = "rigour.iam.bootstrap.tenant-admin")
public final class TenantAdminBootstrapProperties {
    private boolean enabled;
    private String tenantCode;
    private String username;
    private String displayName;
    private int minimumPasswordLength = 14;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String value) { tenantCode = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public int getMinimumPasswordLength() { return minimumPasswordLength; }
    public void setMinimumPasswordLength(int value) { minimumPasswordLength = value; }
}
