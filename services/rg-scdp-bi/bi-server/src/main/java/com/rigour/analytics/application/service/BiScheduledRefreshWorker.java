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
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "bi-schedule-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });
    private final Map<Key, Completion> pending = new ConcurrentHashMap<>();

    public BiScheduledRefreshWorker(
            BiScheduleCoordinator coordinator, SupplyDashboardRefreshService refresh) {
        this.coordinator = coordinator;
        this.refresh = refresh;
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
                var run = refresh.refreshConfiguredTenant(key.tenant().toString());
                done = new Completion(run.statusCode(), run.failureReason());
            } catch (RuntimeException e) {
                done = new Completion("FAILED", "BI刷新失败，请检查刷新记录");
            } finally {
                beat.cancel(false);
            }
            pending.put(key, done);
            acknowledge(key, done);
        }
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
