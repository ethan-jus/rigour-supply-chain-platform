package com.rigour.integration.application.service.dhb;

import com.rigour.integration.api.v1.model.*;
import com.rigour.integration.application.port.out.DhbPageSyncJobStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 持久化状态和唯一活动任务；关闭页面不会取消后台执行。 */
@Service
public class DhbPageSyncJobService {
    private static final Logger log = LoggerFactory.getLogger(DhbPageSyncJobService.class);
    private final DhbSyncOrchestrationService delegate;
    private final DhbPageSyncJobStore store;
    private final Clock clock;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> new Thread(r, "dhb-page-sync"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            r -> { var t = new Thread(r, "dhb-page-heartbeat"); t.setDaemon(true); return t; });
    private final Set<Key> owned = ConcurrentHashMap.newKeySet();
    public DhbPageSyncJobService(DhbSyncOrchestrationService delegate, DhbPageSyncJobStore store, Clock clock) {
        this.delegate = delegate; this.store = store; this.clock = clock;
        heartbeat.scheduleWithFixedDelay(this::beat, 15, 15, TimeUnit.SECONDS);
    }
    public DhbPageSyncJob start(CallerIdentity caller, UUID requestId, DhbPageSyncCommand command) {
        DhbSyncOrchestrationService.requireManualCaller(caller);
        if (requestId == null || command == null || !Boolean.TRUE.equals(command.incremental())
                || !Set.of(DhbPageSyncCommand.Scope.CUSTOMER, DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE).contains(command.scope()))
            throw new IllegalArgumentException("后台任务只支持客户或订单包增量同步");
        delegate.validatePageTarget(caller, command);
        var job = store.reserve(caller.tenantId(), requestId, command, clock.instant());
        if (!job.jobId().equals(requestId) || !store.claim(caller.tenantId(), requestId, clock.instant()))
            return observed(caller.tenantId(), job);
        var key = new Key(caller.tenantId(), requestId); owned.add(key);
        try {
            workers.execute(() -> {
                try {
                    var result = delegate.runPage(caller, command,
                            stage -> store.progress(key.tenant(), key.id(), stage, clock.instant()), true);
                    store.finish(key.tenant(), key.id(), "FAILED".equals(result.status()) ? "FAILED" : "SUCCEEDED",
                            "同步结束，请查看各项结果", result, clock.instant());
                } catch (RuntimeException error) {
                    log.error("订货宝后台同步结束异常 jobId={} type={}", key.id(), error.getClass().getSimpleName(), error);
                    // 跨服务响应丢失可能仍在执行，不能标记成可立即重试的失败。
                    boolean uncertain = error instanceof com.rigour.integration.application.port.out.CrmDhbDomainSyncClient.OutcomeUnknown;
                    store.finish(key.tenant(), key.id(), uncertain ? "UNKNOWN" : "FAILED",
                            uncertain ? "跨服务状态暂不可确认，请核对后台任务，勿重复提交" : "后台执行失败，请查看同步批次问题后重试",
                            null, clock.instant());
                } finally { owned.remove(key); }
            });
        } catch (RejectedExecutionException full) {
            owned.remove(key);
            store.finish(key.tenant(), key.id(), "FAILED", "后台任务队列已满，本次未执行", null, clock.instant());
        }
        return get(caller, requestId);
    }
    public DhbPageSyncJob get(CallerIdentity caller, UUID jobId) {
        DhbSyncOrchestrationService.requireManualCaller(caller);
        return observed(caller.tenantId(), store.find(caller.tenantId(), jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "同步任务不存在", List.of())));
    }
    public DhbPageSyncJob latest(CallerIdentity caller, UUID connector, String scope) {
        DhbSyncOrchestrationService.requireManualCaller(caller);
        return store.latest(caller.tenantId(), connector, scope).map(j -> observed(caller.tenantId(), j)).orElse(null);
    }
    private DhbPageSyncJob observed(UUID tenant, DhbPageSyncJob job) {
        if (Set.of("QUEUED", "RUNNING").contains(job.status())
                && job.heartbeatAt().isBefore(clock.instant().minusSeconds(90))) {
            // 只改变展示，不抢占旧任务、不解锁。恢复心跳或最终回执后会显示真实结果。
            return new DhbPageSyncJob(job.jobId(), job.connectorId(), job.scope(), "UNKNOWN",
                    "后台心跳中断，执行结果待核实；请勿重复提交", job.startedAt(), job.heartbeatAt(), null, job.result());
        }
        return job;
    }
    private void beat() {
        for (var key : owned) try { store.heartbeat(key.tenant(), key.id(), clock.instant()); }
        catch (RuntimeException e) { log.warn("同步任务心跳更新失败 jobId={}", key.id()); }
    }
    @PreDestroy public void close() { heartbeat.shutdown(); workers.shutdown(); }
    private record Key(UUID tenant, UUID id) {}
}
