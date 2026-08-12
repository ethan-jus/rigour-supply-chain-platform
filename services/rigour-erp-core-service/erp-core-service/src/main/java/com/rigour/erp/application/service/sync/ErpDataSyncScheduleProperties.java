package com.rigour.erp.application.service.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ERP 订货宝统一数据同步调度配置。 */
@ConfigurationProperties(prefix = "rigour.erp.sync")
public class ErpDataSyncScheduleProperties {
    /** 默认关闭，避免未确认 Integration 目标时启动服务即触发全量同步。 */
    private boolean enabled;
    /** Spring 六字段 cron 表达式，默认每小时 00 分和 30 分执行。 */
    private String cron = "0 0/30 * * * ?";
    /** 单类数据同步最多读取的页数或库存批次数。 */
    private int maxPages = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
}
