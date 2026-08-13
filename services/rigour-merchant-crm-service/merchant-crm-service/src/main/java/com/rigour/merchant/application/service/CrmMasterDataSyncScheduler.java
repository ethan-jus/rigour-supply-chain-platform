package com.rigour.merchant.application.service;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.merchant.application.port.out.DhbCrmSyncTargetDiscoveryClient;
import com.rigour.shared.context.CallerIdentity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** CRM 定时同步调度器；手动和定时任务共用同一同步服务。 */
@Component
public final class CrmMasterDataSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(CrmMasterDataSyncScheduler.class);
    private final CrmMasterDataSyncService service;
    private final DhbCrmSyncTargetDiscoveryClient discovery;
    private final CrmSyncScheduleProperties properties;

    public CrmMasterDataSyncScheduler(CrmMasterDataSyncService service,
                                      DhbCrmSyncTargetDiscoveryClient discovery,
                                      CrmSyncScheduleProperties properties) {
        this.service = service;
        this.discovery = discovery;
        this.properties = properties;
    }

    @Scheduled(cron = "${rigour.crm.sync.cron:0 10/30 * * * ?}")
    public void synchronize() {
        if (!properties.isEnabled()) return;
        if (properties.getMaxPages() < 1 || properties.getMaxPages() > 100) {
            throw new IllegalStateException("rigour.crm.sync.max-pages必须在1到100之间");
        }
        List<SyncTargetView> targets;
        try {
            targets = uniquePerTenant(discovery.discover(CrmMasterDataSyncService.discoveryCaller()));
        } catch (RuntimeException error) {
            log.error("CRM定时同步目标发现失败 reason={}", oneLine(error.getMessage()));
            return;
        }
        for (SyncTargetView target : targets) {
            CallerIdentity caller = CrmMasterDataSyncService.tenantServiceCaller(target.tenantId());
            try {
                service.runScheduled(caller, target.connectorId(), properties.getMaxPages());
            } catch (RuntimeException error) {
                log.error("CRM定时同步失败 taskId={} tenantId={} connectorId={} reason={}",
                        target.taskId(), target.tenantId(), target.connectorId(),
                        oneLine(error.getMessage()));
            }
        }
    }

    private static List<SyncTargetView> uniquePerTenant(List<SyncTargetView> values) {
        if (values == null) return List.of();
        Map<UUID, Map<UUID, SyncTargetView>> grouped = new LinkedHashMap<>();
        values.stream().filter(item -> item != null && item.tenantId() != null
                        && item.connectorId() != null)
                .forEach(item -> grouped.computeIfAbsent(item.tenantId(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(item.connectorId(), item));
        List<SyncTargetView> result = new java.util.ArrayList<>();
        grouped.forEach((tenant, connectors) -> {
            if (connectors.size() == 1) result.add(connectors.values().iterator().next());
            else log.error("CRM定时同步目标不唯一，跳过租户 tenantId={} connectorIds={}",
                    tenant, connectors.keySet());
        });
        return List.copyOf(result);
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
