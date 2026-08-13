package com.rigour.merchant.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** CRM 订货宝定时同步配置。 */
@ConfigurationProperties("rigour.crm.sync")
public class CrmSyncScheduleProperties {
    private boolean enabled = true;
    private int maxPages = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
}
