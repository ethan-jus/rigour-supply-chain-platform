package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/** 与销售看板共用刷新配置；集中调度接管后由同一 BI 任务更新费用批次。 */
@Service
public class CostDashboardRefreshService {
    private static final Logger log = LoggerFactory.getLogger(CostDashboardRefreshService.class);
    private final CostDashboardStore store;
    private final CostDashboardRefresh refresh;
    private final SupplyDashboardStore supply;
    private final boolean enabled;
    private final boolean scheduledEnabled;
    private final BiScheduleCoordinator schedules;

    public CostDashboardRefreshService(
            CostDashboardStore store,
            CostDashboardRefresh refresh,
            SupplyDashboardStore supply,
            @Value("${rigour.analytics.cost-dashboard.enabled:true}") boolean enabled,
            @Value("${rigour.analytics.supply-dashboard.refresh.enabled:true}")
                    boolean scheduledEnabled,
            BiScheduleCoordinator schedules) {
        this.store = store;
        this.refresh = refresh;
        this.supply = supply;
        this.enabled = enabled;
        this.scheduledEnabled = scheduledEnabled;
        this.schedules = schedules;
    }

    @Scheduled(
            fixedDelayString =
                    "${rigour.analytics.supply-dashboard.refresh.fixed-delay-ms:3600000}",
            initialDelayString =
                    "${rigour.analytics.supply-dashboard.refresh.initial-delay-ms:60000}")
    public void scheduled() {
        if (!enabled || !scheduledEnabled) return;
        Set<String> tenants = new LinkedHashSet<>();
        try {
            tenants.addAll(store.tenants());
        } catch (RuntimeException e) {
            log.warn("cost_snapshot_registry_failed errorType={}", e.getClass().getSimpleName());
        }
        try {
            tenants.addAll(supply.refreshTenantIds());
        } catch (RuntimeException e) {
            log.warn("cost_snapshot_tenants_failed errorType={}", e.getClass().getSimpleName());
        }
        for (String tenant : tenants)
            try {
                // 与销售任务相同：已接管（包括停用）的租户不再走旧定时入口。
                // 协调服务不可用时跳过，不能绕过集中计划的停用或互斥约束。
                if (schedules.managed(UUID.fromString(tenant))) continue;
                refreshTenant(tenant);
            } catch (RuntimeException e) {
                log.warn(
                        "cost_snapshot_schedule_failed tenant={} errorType={}",
                        tenant,
                        e.getClass().getSimpleName());
            }
    }

    public void refreshConfiguredTenant(String tenant) {
        // 集中计划的启停由 Integration 管理，不受旧定时入口开关影响。
        if (enabled) refreshTenant(tenant);
    }

    public void refreshTenant(String tenant) {
        if (!enabled) throw new IllegalStateException("成本看板尚未开放");
        for (int attempt = 1; attempt <= 3; attempt++)
            try {
                store.register(tenant);
                refresh.refresh(tenant);
                log.info("cost_snapshot_committed tenant={}", tenant);
                return;
            } catch (RuntimeException e) {
                log.warn(
                        "cost_snapshot_failed tenant={} attempt={} errorType={} versionChanged={}",
                        tenant,
                        attempt,
                        e.getClass().getSimpleName(),
                        "SOURCE_VERSION_CHANGED".equals(e.getMessage()));
                // 每次调用进入新的投影事务，不能在已回滚的事务中重试 SQL。
                if (attempt < 3 && e instanceof ConcurrencyFailureException
                        || attempt == 1 && "SOURCE_VERSION_CHANGED".equals(e.getMessage())) continue;
                try {
                    refresh.failed(tenant);
                } catch (RuntimeException markingFailure) {
                    // 记录失败本身也可能遇到锁冲突，不能掩盖原始错误或阻断旧完整批次兜底。
                    e.addSuppressed(markingFailure);
                    log.warn("cost_snapshot_failure_state_failed tenant={} errorType={}",
                            tenant, markingFailure.getClass().getSimpleName());
                }
                throw new CostDashboardFailure("COST_SOURCE_UNAVAILABLE", 503, "费用来源暂不可用，请稍后刷新", e);
            }
    }
}
