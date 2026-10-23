package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.BiScheduleCoordinator;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/** 执行器：频率、启停、下一次时间、互斥占位与结果均由 Integration 决定。 */
@Service
public class BiScheduledRefreshWorker {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BiScheduledRefreshWorker.class);
    private final BiScheduleCoordinator coordinator;
    private final SupplyDashboardRefreshService refresh;
    private final CostDashboardRefreshService costs;
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "bi-schedule-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });
    private final Map<Key, Completion> pending = new ConcurrentHashMap<>();

    public BiScheduledRefreshWorker(
            BiScheduleCoordinator coordinator,
            SupplyDashboardRefreshService refresh,
            CostDashboardRefreshService costs) {
        this.coordinator = coordinator;
        this.refresh = refresh;
        this.costs = costs;
    }

    @Scheduled(
            fixedDelayString = "${rigour.analytics.supply-dashboard.schedule-poll-ms:30000}",
            initialDelay = 30000)
    public void consume() {
        for (var e : pending.entrySet()) acknowledge(e.getKey(), e.getValue());
        List<BiScheduleCoordinator.Work> work;
        try {
            work = coordinator.due();
        } catch (RuntimeException e) {
            log.warn("集中调度暂不可用，本轮不执行BI计划");
            return;
        }
        for (var item : work) {
            var key = new Key(item.tenantId(), UUID.randomUUID());
            try {
                if (!coordinator.claim(key.tenant(), item.version(), key.token())) continue;
            } catch (RuntimeException e) {
                log.warn("BI计划认领结果待核实，不重复认领 tenant={}", item.tenantId());
                continue;
            }
            var beat =
                    heartbeats.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    coordinator.heartbeat(key.tenant(), key.token());
                                } catch (RuntimeException e) {
                                    log.warn("BI计划心跳发送失败 tenant={}", key.tenant());
                                }
                            },
                            15,
                            15,
                            TimeUnit.SECONDS);
            Completion done;
            try {
                done = refreshDashboards(key.tenant());
            } finally {
                beat.cancel(false);
            }
            pending.put(key, done);
            acknowledge(key, done);
        }
    }

    private Completion refreshDashboards(UUID tenant) {
        Completion result;
        try {
            var run = refresh.refreshConfiguredTenant(tenant.toString());
            result = new Completion(run.statusCode(), run.failureReason());
        } catch (RuntimeException e) {
            log.warn("BI经营数据刷新失败 tenant={}", tenant, e);
            result = new Completion("FAILED", "BI经营数据刷新失败，请检查刷新记录");
        }
        // 已有同租户刷新占锁时，本次不额外启动费用批次。
        if ("SKIPPED".equals(result.status())) return result;
        try {
            // 复用同一次计划认领和心跳；经营源失败不阻止独立的费用源更新。
            costs.refreshConfiguredTenant(tenant.toString());
        } catch (RuntimeException e) {
            log.warn("BI费用数据刷新失败 tenant={}", tenant, e);
            return new Completion(
                    "FAILED".equals(result.status()) ? "FAILED" : "PARTIAL",
                    "费用数据刷新失败，请检查费用来源；经营数据状态：" + result.status());
        }
        return result;
    }

    private void acknowledge(Key key, Completion done) {
        try {
            coordinator.complete(key.tenant(), key.token(), done.status(), done.message());
            pending.remove(key);
        } catch (RuntimeException e) {
            log.warn("BI刷新回执暂未送达，将重发同一回执，不重跑刷新 tenant={}", key.tenant());
        }
    }

    @PreDestroy
    public void close() {
        heartbeats.shutdown();
    }

    private record Key(UUID tenant, UUID token) {}

    private record Completion(String status, String message) {}
}
