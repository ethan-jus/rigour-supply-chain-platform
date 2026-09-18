package com.rigour.integration.application.service.dhb;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.application.port.out.*;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/** 自动订单/资金/履约分别推进；某个对象未核对完不阻塞其他对象的来源读取。 */
@Service
public class DhbContinuousOrderSyncService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DhbContinuousOrderSyncService.class);
    private final DhbIntegrationStore targets;
    private final DhbOrderSyncService orders;
    private final DhbObjectCheckpointStore checkpoints;
    private final DhbOrchestrationLease lease;
    private final DhbSyncOrchestrationProperties properties;

    public DhbContinuousOrderSyncService(
            DhbIntegrationStore targets,
            DhbOrderSyncService orders,
            DhbObjectCheckpointStore checkpoints,
            DhbOrchestrationLease lease,
            DhbSyncOrchestrationProperties properties) {
        this.targets = targets;
        this.orders = orders;
        this.checkpoints = checkpoints;
        this.lease = lease;
        this.properties = properties;
    }

    public void synchronize() {
        properties.validate();
        var bootstrap = properties.scheduledWindowFromInstant();
        if (bootstrap == null) throw new IllegalStateException("请配置首次同步开始时间");
        Instant end = Instant.now().minusSeconds(120);
        for (var target : targets.activeOrderSyncTargets()) {
            try {
                lease.execute(
                        target.tenantId(),
                        target.connectorId(),
                        "DHB_OBJECT_SYNC",
                        () -> {
                            for (String scope :
                                    List.of(
                                            "SALES_ORDER",
                                            "RECEIPT",
                                            "SHIPMENT",
                                            "TRANSFER",
                                            "PAYMENT")) {
                                try {
                                    Instant cursor =
                                            checkpoints.successfulTo(
                                                    target.tenantId(), target.connectorId(), scope);
                                    Instant from =
                                            cursor == null ? bootstrap : cursor.minusSeconds(900);
                                    if (from.isBefore(bootstrap)) from = bootstrap;
                                    if (!from.isBefore(end)) continue;
                                    var result =
                                            orders.runOrderPull(
                                                    DhbSyncOrchestrationService.serviceCaller(
                                                            target.tenantId()),
                                                    target.taskId(),
                                                    new SyncRunCommand(
                                                            from, end, null, null, null, scope),
                                                    properties.getMaxPages());
                                    if ("SUCCEEDED".equals(result.status()))
                                        checkpoints.completed(
                                                target.tenantId(),
                                                target.connectorId(),
                                                scope,
                                                end,
                                                result.runId());
                                    else
                                        log.warn(
                                                "对象同步未完成，保留原游标 tenant={} scope={} status={} run={}",
                                                target.tenantId(),
                                                scope,
                                                result.status(),
                                                result.runId());
                                } catch (RuntimeException e) {
                                    log.error(
                                            "对象同步失败，其他对象继续 tenant={} scope={}",
                                            target.tenantId(),
                                            scope,
                                            e);
                                }
                            }
                            return null;
                        });
            } catch (RuntimeException e) {
                log.warn(
                        "连接器本轮未取得同步租约 tenant={} connector={}",
                        target.tenantId(),
                        target.connectorId(),
                        e);
            }
        }
    }
}
