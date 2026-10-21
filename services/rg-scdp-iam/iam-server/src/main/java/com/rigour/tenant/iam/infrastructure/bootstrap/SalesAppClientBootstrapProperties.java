package com.rigour.tenant.iam.infrastructure.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立销售 App PUBLIC 客户端；标准调试基座只能在显式 DEV 配置中注册。 */
@ConfigurationProperties(prefix = "rigour.iam.bootstrap.sales-app-client")
public final class SalesAppClientBootstrapProperties {
    public static final String APP_CALLBACK = "com.rigour.workbench://auth/callback";
    public static final String DEBUG_CALLBACK = "uniappx://rigour-sales/auth/callback";

    private boolean enabled;
    private String clientId = "rigour-sales-app-android";
    private String clientName = "瑞盖销售工作台";
    private String redirectUri = APP_CALLBACK;
    private boolean allowDebugBase;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public boolean isAllowDebugBase() { return allowDebugBase; }
    public void setAllowDebugBase(boolean allowDebugBase) { this.allowDebugBase = allowDebugBase; }

    public void validate(boolean developmentEnvironment) {
        if (clientId == null || !clientId.matches("rigour-sales-app-(android|ios|harmony)(-dev)?")
                || clientName == null || clientName.isBlank()) {
            throw new IllegalStateException("Sales App requires a dedicated mobile client id and name");
        }
        if (DEBUG_CALLBACK.equals(redirectUri)) {
            if (!developmentEnvironment || !allowDebugBase || !clientId.endsWith("-dev")) {
                throw new IllegalStateException("Sales App debug callback requires dev/local profile, opt-in and a dev client id");
            }
        } else if (!APP_CALLBACK.equals(redirectUri)) {
            throw new IllegalStateException("Sales App callback must exactly match the registered company callback");
        }
    }
}
