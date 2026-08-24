package com.rigour.integration.application.service.dhb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 订货宝统一同步编排配置。 */
@ConfigurationProperties(prefix = "rigour.integration.dhb.orchestration")
public class DhbSyncOrchestrationProperties {
    public static final String DEFAULT_CRON = "0 5/30 * * * ?";

    private boolean enabled = true;
    private String cron = DEFAULT_CRON;
    private int maxPages = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }

    public void validate() {
        if (maxPages < 1 || maxPages > 100) {
            throw new IllegalStateException("订货宝统一同步max-pages必须在1到100之间");
        }
        if (cron == null || cron.isBlank()) {
            throw new IllegalStateException("订货宝统一同步cron不能为空");
        }
    }
}
