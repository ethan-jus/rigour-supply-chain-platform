package com.rigour.order.application.service.dhb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Order Center订货宝同步调度配置。
 *
 * <p>Nacos只保存全局调度策略；租户、连接器及任务清单由Integration运行时动态发现，
 * 不保存订货宝账号密码或租户用户UUID。</p>
 */
@ConfigurationProperties(prefix = "rigour.order.dhb.sync")
public class DhbOrderSyncScheduleProperties {
    /** 是否启用Order Center内部定时同步，默认关闭，避免未配置任务时误拉全量。 */
    private boolean enabled;
    /** Spring六字段cron表达式，默认每小时00分和30分执行。 */
    private String cron = "0 20/30 * * * ?";
    /** 每次同步最多读取页数，防止单次任务无界拉取。 */
    private int maxPages = 100;
    /** 增量窗口重叠分钟数，用于覆盖供应商时间边界和分页期间的更新。 */
    private int overlapMinutes = 5;
    /** 付款单没有可靠更新时间条件，按此间隔做一次完整对账。 */
    private int fullReconcileHours = 24;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public int getOverlapMinutes() { return overlapMinutes; }
    public void setOverlapMinutes(int overlapMinutes) { this.overlapMinutes = overlapMinutes; }
    public int getFullReconcileHours() { return fullReconcileHours; }
    public void setFullReconcileHours(int fullReconcileHours) { this.fullReconcileHours = fullReconcileHours; }
}
