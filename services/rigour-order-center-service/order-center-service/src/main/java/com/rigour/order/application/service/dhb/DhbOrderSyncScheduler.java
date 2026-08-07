package com.rigour.order.application.service.dhb;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncResult;
import com.rigour.order.application.port.out.DhbOrderSyncCheckpointStore;
import com.rigour.order.application.port.out.DhbSyncTargetDiscoveryClient;
import com.rigour.shared.context.CallerIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Order Center内部订货宝同步调度器。
 *
 * <p>前端不会触发此任务；每次调度先从Integration动态发现启用目标，再以带目标租户范围的
 * 服务身份调用Integration。成功返回并完成本地业务落库后才推进游标。</p>
 */
@Component
public final class DhbOrderSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(DhbOrderSyncScheduler.class);
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
    private static final String OBJECT_TYPE = "ORDER";
    private static final Set<String> REQUIRED_COMPLETION = Set.of(
            "ORDER", "SHIPMENT", "SHIPMENT_LOGISTICS", "RETURN", "RECEIPT", "PAYMENT");
    private static final Set<String> SERVICE_PERMISSIONS = Set.of(
            "integration:dhb:read", "integration:dhb:write", "integration:dhb:sync-discovery");

    private final DhbOrderSyncService syncService;
    private final DhbOrderSyncCheckpointStore checkpointStore;
    private final DhbSyncTargetDiscoveryClient targetDiscoveryClient;
    private final DhbOrderSyncScheduleProperties properties;
    private final Clock clock;

    public DhbOrderSyncScheduler(DhbOrderSyncService syncService,
                                 DhbOrderSyncCheckpointStore checkpointStore,
                                 DhbSyncTargetDiscoveryClient targetDiscoveryClient,
                                 DhbOrderSyncScheduleProperties properties,
                                 Clock clock) {
        this.syncService = syncService;
        this.checkpointStore = checkpointStore;
        this.targetDiscoveryClient = targetDiscoveryClient;
        this.properties = properties;
        this.clock = clock;
    }

    /** 每小时00分和30分触发，逐个处理Integration动态发现的租户任务。 */
    @Scheduled(cron = "${rigour.order.dhb.sync.cron:0 0/30 * * * ?}")
    public void synchronize() {
        if (!properties.isEnabled()) {
            log.info("DHB订单定时同步未启用，跳过本次调度");
            return;
        }
        validateProperties();

        List<SyncTargetView> targets;
        try {
            targets = targetDiscoveryClient.discover(serviceCaller(null));
        } catch (RuntimeException error) {
            log.error("DHB订单定时同步目标发现失败，当前批次不执行任何租户同步 reason={}",
                    error.getMessage());
            return;
        }
        if (targets == null || targets.isEmpty()) {
            log.info("DHB订单定时同步未发现启用目标，跳过本次调度");
            return;
        }
        for (SyncTargetView target : targets) {
            synchronizeOne(target);
        }
    }

    private void synchronizeOne(SyncTargetView target) {
        if (target == null || target.taskId() == null || target.tenantId() == null
                || target.connectorId() == null) {
            log.error("DHB订单定时同步目标不完整，跳过本次任务");
            return;
        }
        String tenantId = target.tenantId().toString();
        UUID connectorId = target.connectorId();
        UUID runId = null;
        Instant windowTo = clock.instant();
        try {
            CallerIdentity caller = serviceCaller(target.tenantId());
            Instant lastSuccessAt = checkpointStore.lastSuccessAt(tenantId, connectorId, OBJECT_TYPE);
            Instant windowFrom = incrementalFrom(lastSuccessAt, windowTo);
            DhbOrderSyncCommand command = new DhbOrderSyncCommand(
                    Boolean.TRUE, properties.getMaxPages(), windowFrom, windowFrom == null ? null : windowTo);
            DhbOrderSyncResult result = syncService.runScheduled(caller, connectorId, command);
            runId = result.runId();
            if (!result.completedObjects().containsAll(REQUIRED_COMPLETION)) {
                throw new IllegalStateException("Integration未完成ORDER对象拉取，游标不推进");
            }
            checkpointStore.markSucceeded(tenantId, connectorId, OBJECT_TYPE, runId, windowTo);
            log.info("DHB订单定时同步成功 taskId={} tenantId={} connectorId={} runId={} fetched={} changed={} incremental={}",
                    target.taskId(), tenantId, connectorId, runId, result.fetched(), result.changed(),
                    windowFrom != null);
        } catch (RuntimeException error) {
            checkpointStore.markFailed(tenantId, connectorId, OBJECT_TYPE, runId, error.getMessage());
            log.error("DHB订单定时同步失败 taskId={} tenantId={} connectorId={} runId={} reason={}",
                    target.taskId(), tenantId, connectorId, runId, error.getMessage());
        }
    }

    private void validateProperties() {
        if (properties.getMaxPages() < 1 || properties.getMaxPages() > 100) {
            throw new IllegalStateException("rigour.order.dhb.sync.max-pages必须在1到100之间");
        }
        if (properties.getOverlapMinutes() < 0 || properties.getOverlapMinutes() > 24 * 60) {
            throw new IllegalStateException("rigour.order.dhb.sync.overlap-minutes必须在0到1440之间");
        }
    }

    private Instant incrementalFrom(Instant lastSuccessAt, Instant windowTo) {
        if (lastSuccessAt == null || !lastSuccessAt.isBefore(windowTo)) return null;
        return lastSuccessAt.minus(Duration.ofMinutes(properties.getOverlapMinutes()));
    }

    private CallerIdentity serviceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ORDER_SYNC_SERVICE"), SERVICE_PERMISSIONS);
    }
}
