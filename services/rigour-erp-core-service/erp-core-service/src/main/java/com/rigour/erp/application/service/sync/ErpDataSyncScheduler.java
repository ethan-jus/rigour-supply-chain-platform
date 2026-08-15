package com.rigour.erp.application.service.sync;

import com.rigour.erp.api.v1.model.ErpDataSyncCommand;
import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.port.out.DhbProductSyncTargetDiscoveryClient;
import com.rigour.erp.application.port.out.DhbSupplySyncTargetDiscoveryClient;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.sync.SyncConflictClassifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ERP 内部订货宝统一同步调度器。
 *
 * <p>调度器只负责发现目标、确定依赖顺序和创建租户范围的 SERVICE 身份；实际拉取、幂等落库
 * 和批次状态仍由统一 {@link ErpDataSyncService} 完成，手动接口与定时任务不会形成两套逻辑。</p>
 */
@Component
public final class ErpDataSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(ErpDataSyncScheduler.class);
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> DISCOVERY_PERMISSIONS =
            Set.of("integration:dhb:sync-discovery");
    private static final List<String> PRODUCT_OBJECT_TYPES = List.of(
            "CATEGORY", "BRAND", "SPECIFICATION", "TAG", "PRODUCT_SPU");
    private static final List<String> SUPPLY_OBJECT_TYPES = List.of(
            "SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER", "PURCHASE_RETURN",
            "WAREHOUSING_RECEIPT", "INVENTORY");

    private final ErpDataSyncService syncService;
    private final DhbProductSyncTargetDiscoveryClient productDiscovery;
    private final DhbSupplySyncTargetDiscoveryClient supplyDiscovery;
    private final ErpDataSyncScheduleProperties properties;

    public ErpDataSyncScheduler(ErpDataSyncService syncService,
                                DhbProductSyncTargetDiscoveryClient productDiscovery,
                                DhbSupplySyncTargetDiscoveryClient supplyDiscovery,
                                ErpDataSyncScheduleProperties properties) {
        this.syncService = syncService;
        this.productDiscovery = productDiscovery;
        this.supplyDiscovery = supplyDiscovery;
        this.properties = properties;
    }

    /** 每小时 00 分和 30 分触发；Integration 任务的启停状态决定实际同步目标。 */
    @Scheduled(cron = "${rigour.erp.sync.cron:0 0/30 * * * ?}")
    public void synchronize() {
        if (!properties.isEnabled()) {
            log.info("ERP订货宝定时同步未启用，跳过本次调度");
            return;
        }
        validateProperties();

        List<SyncTargetView> productTargets;
        List<SyncTargetView> supplyTargets;
        try {
            CallerIdentity discoveryCaller = discoveryCaller();
            productTargets = targets(productDiscovery.discover(discoveryCaller), "PRODUCT_MASTER_DATA");
            supplyTargets = targets(supplyDiscovery.discover(discoveryCaller), "SUPPLY_CHAIN_DATA");
        } catch (RuntimeException error) {
            log.error("ERP定时同步目标发现失败，本次不执行任何租户同步 reason={}",
                    oneLine(error.getMessage()));
            return;
        }

        if (productTargets.isEmpty() && supplyTargets.isEmpty()) {
            log.info("ERP订货宝定时同步未发现启用目标，跳过本次调度");
            return;
        }
        productTargets.forEach(target -> synchronizeTarget("PRODUCT_MASTER_DATA", target,
                PRODUCT_OBJECT_TYPES));
        supplyTargets.forEach(target -> synchronizeTarget("SUPPLY_CHAIN_DATA", target,
                SUPPLY_OBJECT_TYPES));
    }

    private void synchronizeTarget(String taskType, SyncTargetView target, List<String> objectTypes) {
        CallerIdentity caller = serviceCaller(target.tenantId());
        for (String objectType : objectTypes) {
            try {
                ErpDataSyncResult result = syncService.runScheduled(caller, target.connectorId(),
                        new ErpDataSyncCommand(objectType, properties.getMaxPages()));
                log.info("ERP定时同步成功 taskType={} taskId={} tenantId={} connectorId={} objectType={} runId={} fetched={} created={} changed={} duplicates={} rejected={} pages={}",
                        taskType, target.taskId(), target.tenantId(), target.connectorId(), objectType,
                        result.runId(), result.fetched(), result.created(), result.changed(),
                        result.duplicates(), result.rejected(), result.pages());
            } catch (RuntimeException error) {
                if (SyncConflictClassifier.isAlreadyRunning(error)) {
                    log.info("ERP定时同步跳过，已有同范围任务运行 taskType={} taskId={} tenantId={} connectorId={} objectType={}",
                            taskType, target.taskId(), target.tenantId(), target.connectorId(), objectType);
                    continue;
                }
                log.error("ERP定时同步失败 taskType={} taskId={} tenantId={} connectorId={} objectType={} reason={}",
                        taskType, target.taskId(), target.tenantId(), target.connectorId(), objectType,
                        oneLine(error.getMessage()));
            }
        }
    }

    private List<SyncTargetView> targets(List<SyncTargetView> values, String taskType) {
        if (values == null) return List.of();
        Map<UUID, Map<UUID, SyncTargetView>> byTenant = new LinkedHashMap<>();
        values.stream()
                .filter(item -> item != null && item.taskId() != null && item.tenantId() != null
                        && item.connectorId() != null)
                .forEach(item -> byTenant.computeIfAbsent(item.tenantId(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(item.connectorId(), item));
        List<SyncTargetView> result = new java.util.ArrayList<>();
        byTenant.forEach((tenantId, connectors) -> {
            if (connectors.size() > 1) {
                log.error("ERP定时同步目标不唯一，跳过租户 taskType={} tenantId={} connectorIds={}",
                        taskType, tenantId, connectors.keySet());
                return;
            }
            result.add(connectors.values().iterator().next());
        });
        return List.copyOf(result);
    }

    private void validateProperties() {
        if (properties.getMaxPages() < 1 || properties.getMaxPages() > 100) {
            throw new IllegalStateException("rigour.erp.sync.max-pages必须在1到100之间");
        }
    }

    private static CallerIdentity discoveryCaller() {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, null, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ERP_SYNC_SERVICE"), DISCOVERY_PERMISSIONS);
    }

    private static CallerIdentity serviceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ERP_SYNC_SERVICE"),
                Set.of("integration:dhb:read"));
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
