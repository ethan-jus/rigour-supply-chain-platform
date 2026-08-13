package com.rigour.erp.application.service.supply;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.port.out.DhbSupplySyncTargetDiscoveryClient;
import com.rigour.erp.application.port.out.DhbSupplyDataClient;
import com.rigour.erp.application.port.out.SupplyDataStore;
import com.rigour.erp.application.port.out.SupplyDataStore.ImportResult;
import com.rigour.erp.application.port.out.SupplyDataStore.RunStatistics;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** ERP 手动编排供应商、采购、仓储和库存同步。 */
@Service
public final class SupplyDataSyncService {
    private static final Logger log = LoggerFactory.getLogger(SupplyDataSyncService.class);
    private static final UUID SERVICE_ID = UUID.nameUUIDFromBytes(
            "service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8));
    private final DhbSupplyDataClient client;
    private final DhbSupplySyncTargetDiscoveryClient discovery;
    private final SupplyDataStore store;

    public SupplyDataSyncService(DhbSupplyDataClient client,
                                 DhbSupplySyncTargetDiscoveryClient discovery,
                                 SupplyDataStore store) {
        this.client = client; this.discovery = discovery; this.store = store;
    }

    public ErpDataSyncResult run(SupplyDataObjectType type, int maxPages) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null || caller.userId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission("erp:supply:write");
        if (maxPages < 1 || maxPages > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxPages必须在1到100之间", List.of());
        }
        SyncTargetView target = uniqueTarget(caller);
        return runWithCaller(caller, target.connectorId(), caller.userId(), type, maxPages, false);
    }

    /** 供 ERP 内部定时编排器调用；目标连接器已经由调度器完成发现和校验。 */
    public ErpDataSyncResult runScheduled(CallerIdentity caller, UUID connectorId,
                                          SupplyDataObjectType type, int maxPages) {
        requireScheduledCaller(caller);
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        if (maxPages < 1 || maxPages > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxPages必须在1到100之间", List.of());
        }
        return runWithCaller(caller, connectorId, null, type, maxPages, true);
    }

    private ErpDataSyncResult runWithCaller(CallerIdentity caller, UUID connectorId, UUID actorId,
                                             SupplyDataObjectType type, int maxPages,
                                             boolean scheduled) {
        String tenantId = caller.tenantId().toString();
        UUID runId = scheduled
                ? store.startScheduledRun(tenantId, connectorId, actorId, type, maxPages)
                : store.startRun(tenantId, connectorId, actorId, type, maxPages);
        log.info("ERP供应链数据同步批次已创建 tenantId={} userId={} objectType={} connectorId={} runId={} maxPages={}",
                tenantId, actorId, type, connectorId, runId, maxPages);
        Counts counts = new Counts();
        try {
            List<String> codes = type == SupplyDataObjectType.INVENTORY
                    ? store.sourceProductCodes(tenantId) : List.of();
            DhbSupplyDataClient.Collected collected = client.collect(dataCaller(caller.tenantId()),
                    connectorId, type, maxPages, codes);
            counts.pages = collected.pages();
            collected.suppliers().forEach(item -> counts.add(store.importSupplier(tenantId, runId, item)));
            collected.purchaseOrders().forEach(item -> counts.add(store.importPurchaseOrder(tenantId, runId, item)));
            collected.purchaseReturns().forEach(item -> counts.add(store.importPurchaseReturn(tenantId, runId, item)));
            collected.warehousingReceipts().forEach(item -> counts.add(store.importWarehousingReceipt(tenantId, runId, item)));
            collected.warehouses().forEach(item -> counts.add(store.importWarehouse(tenantId, runId, item)));
            collected.inventoryBalances().forEach(item -> counts.add(store.importInventory(tenantId, runId, item)));
            RunStatistics stats = counts.statistics();
            if (stats.rejected() == 0) {
                store.reconcileSourcePresence(tenantId, runId, seenSourceIds(type, collected));
            }
            store.completeRun(tenantId, runId, stats);
            log.info("ERP供应链数据同步批次完成 tenantId={} objectType={} connectorId={} runId={} fetched={} created={} changed={} duplicates={} rejected={} pages={}",
                    tenantId, type, connectorId, runId, stats.fetched(), stats.created(),
                    stats.changed(), stats.duplicates(), stats.rejected(), stats.pages());
            return new ErpDataSyncResult(runId, type.name(), "SUCCEEDED", connectorId,
                    stats.fetched(), stats.created(), stats.changed(), stats.duplicates(),
                    stats.rejected(), stats.pages(), Instant.now());
        } catch (RuntimeException error) {
            store.failRun(tenantId, runId, counts.statistics(), error);
            log.error("ERP供应链数据同步批次失败 tenantId={} objectType={} connectorId={} runId={} errorType={} reason={}",
                    tenantId, type, connectorId, runId,
                    error.getClass().getSimpleName(), oneLine(error.getMessage()), error);
            throw error;
        }
    }

    private SyncTargetView uniqueTarget(CallerIdentity caller) {
        List<SyncTargetView> targets = discovery.discover(discoveryCaller()).stream()
                .filter(item -> item != null && caller.tenantId().equals(item.tenantId())
                        && item.connectorId() != null)
                .collect(Collectors.collectingAndThen(Collectors.toMap(SyncTargetView::connectorId,
                        item -> item, (a, b) -> a, LinkedHashMap::new), values -> List.copyOf(values.values())));
        if (targets.isEmpty()) throw new AuthorizationDeniedException("integration:dhb:supply-sync-target-not-found");
        if (targets.size() > 1) throw new IllegalStateException("当前租户存在多个启用的订货宝连接器，无法自动确定同步目标");
        return targets.getFirst();
    }

    private static CallerIdentity discoveryCaller() {
        return serviceCaller(null, Set.of("integration:dhb:sync-discovery"));
    }

    private static void requireScheduledCaller(CallerIdentity caller) {
        if (caller == null || caller.tenantId() == null || caller.userId() != null
                || !"SERVICE".equals(caller.principalScope())) {
            throw new AuthorizationDeniedException("tenant-service-caller");
        }
        if (!caller.permissions().contains("integration:dhb:read")
                && !caller.permissions().contains("*:*:*")) {
            throw new AuthorizationDeniedException("integration:dhb:read");
        }
    }
    private static CallerIdentity dataCaller(UUID tenantId) {
        return serviceCaller(tenantId, Set.of("integration:dhb:read"));
    }
    private static CallerIdentity serviceCaller(UUID tenantId, Set<String> permissions) {
        return new CallerIdentity("SERVICE", SERVICE_ID, tenantId, null, null, UUID.randomUUID(),
                0, 0, 0, Set.of("ERP_SUPPLY_SYNC_SERVICE"), permissions);
    }

    private static final class Counts {
        long fetched; long created; long changed; long duplicates; long rejected; int pages;
        void add(ImportResult value) {
            created += value.created(); changed += value.changed();
            duplicates += value.duplicates(); rejected += value.rejected();
            fetched += value.created() + value.changed() + value.duplicates() + value.rejected();
        }
        RunStatistics statistics() { return new RunStatistics(fetched, created, changed, duplicates, rejected, pages); }
    }

    private static Map<String, Set<String>> seenSourceIds(SupplyDataObjectType type,
                                                           DhbSupplyDataClient.Collected collected) {
        Set<String> ids = switch (type) {
            case SUPPLIER -> collected.suppliers().stream().map(item -> item.sourceId()).collect(Collectors.toSet());
            case PURCHASE_ORDER -> collected.purchaseOrders().stream().map(item -> item.sourceId()).collect(Collectors.toSet());
            case PURCHASE_RETURN -> collected.purchaseReturns().stream().map(item -> item.sourceId()).collect(Collectors.toSet());
            case WAREHOUSING_RECEIPT -> collected.warehousingReceipts().stream().map(item -> item.sourceId()).collect(Collectors.toSet());
            case WAREHOUSE -> collected.warehouses().stream().map(item -> item.sourceId()).collect(Collectors.toSet());
            case INVENTORY -> Set.of();
        };
        return type == SupplyDataObjectType.INVENTORY ? Map.of() : Map.of(type.name(), ids);
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
