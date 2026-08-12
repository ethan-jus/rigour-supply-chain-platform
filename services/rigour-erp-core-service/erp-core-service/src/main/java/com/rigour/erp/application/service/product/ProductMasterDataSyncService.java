package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.port.out.DhbProductMasterDataClient;
import com.rigour.erp.application.port.out.DhbProductMasterDataClient.Collected;
import com.rigour.erp.application.port.out.DhbProductSyncTargetDiscoveryClient;
import com.rigour.erp.application.port.out.ProductMasterDataStore;
import com.rigour.erp.application.port.out.ProductMasterDataStore.ImportResult;
import com.rigour.erp.application.port.out.ProductMasterDataStore.RunStatistics;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** ERP 手动编排订货宝商品主数据同步，负责批次、幂等落库和内部状态保护。 */
@Service
public final class ProductMasterDataSyncService {
    private static final Logger log = LoggerFactory.getLogger(ProductMasterDataSyncService.class);
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> DISCOVERY_PERMISSIONS =
            Set.of("integration:dhb:sync-discovery");
    private static final Set<String> DATA_PERMISSIONS = Set.of("integration:dhb:read");

    private final DhbProductMasterDataClient client;
    private final DhbProductSyncTargetDiscoveryClient discoveryClient;
    private final ProductMasterDataStore store;

    public ProductMasterDataSyncService(DhbProductMasterDataClient client,
                                        DhbProductSyncTargetDiscoveryClient discoveryClient,
                                        ProductMasterDataStore store) {
        this.client = client;
        this.discoveryClient = discoveryClient;
        this.store = store;
    }

    public ErpDataSyncResult run(MasterDataObjectType objectType, int maxPages) {
        CallerIdentity caller = requireCaller();
        if (maxPages < 1 || maxPages > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxPages必须在1到100之间", List.of());
        }
        SyncTargetView target = uniqueTarget(caller);
        return runWithCaller(caller, target.connectorId(), caller.userId(), objectType, maxPages, false);
    }

    /** 供 ERP 内部定时编排器调用；目标连接器已经由调度器完成发现和校验。 */
    public ErpDataSyncResult runScheduled(CallerIdentity caller, UUID connectorId,
                                          MasterDataObjectType objectType, int maxPages) {
        requireScheduledCaller(caller);
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        if (maxPages < 1 || maxPages > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxPages必须在1到100之间", List.of());
        }
        return runWithCaller(caller, connectorId, null, objectType, maxPages, true);
    }

    private ErpDataSyncResult runWithCaller(CallerIdentity caller, UUID connectorId, UUID actorId,
                                             MasterDataObjectType objectType, int maxPages,
                                             boolean scheduled) {
        String tenantId = caller.tenantId().toString();
        UUID runId = scheduled
                ? store.startScheduledRun(tenantId, connectorId, actorId, objectType, maxPages)
                : store.startRun(tenantId, connectorId, actorId, objectType, maxPages);
        log.info("ERP商品主数据同步批次已创建 tenantId={} userId={} objectType={} connectorId={} runId={} maxPages={}",
                tenantId, actorId, objectType, connectorId, runId, maxPages);
        Accumulator counts = new Accumulator();
        try {
            Collected collected = client.collect(tenantServiceCaller(caller.tenantId()),
                    connectorId, objectType, maxPages);
            counts.pages = collected.pages();
            importCollected(tenantId, runId, collected, counts);
            RunStatistics statistics = counts.statistics();
            store.completeRun(tenantId, runId, statistics);
            log.info("ERP商品主数据同步批次完成 tenantId={} objectType={} connectorId={} runId={} fetched={} created={} changed={} duplicates={} rejected={} pages={}",
                    tenantId, objectType, connectorId, runId, statistics.fetched(),
                    statistics.created(), statistics.changed(), statistics.duplicates(),
                    statistics.rejected(), statistics.pages());
            return new ErpDataSyncResult(runId, objectType.name(), "SUCCEEDED", connectorId,
                    statistics.fetched(), statistics.created(), statistics.changed(),
                    statistics.duplicates(), statistics.rejected(), statistics.pages(), Instant.now());
        } catch (RuntimeException error) {
            store.failRun(tenantId, runId, counts.statistics(), error);
            log.error("ERP商品主数据同步批次失败 tenantId={} objectType={} connectorId={} runId={} errorType={} reason={}",
                    tenantId, objectType, connectorId, runId,
                    error.getClass().getSimpleName(), oneLine(error.getMessage()), error);
            throw error;
        }
    }

    private void importCollected(String tenantId, UUID runId, Collected collected, Accumulator counts) {
        collected.categories().forEach(item -> counts.add(store.importCategory(tenantId, runId, item)));
        collected.brands().forEach(item -> counts.add(store.importBrand(tenantId, runId, item)));
        collected.specifications().forEach(item -> counts.add(
                store.importSpecification(tenantId, runId, item)));
        collected.tags().forEach(item -> counts.add(store.importTag(tenantId, runId, item)));
        collected.products().forEach(item -> counts.add(store.importProduct(tenantId, runId, item)));
    }

    private SyncTargetView uniqueTarget(CallerIdentity caller) {
        List<SyncTargetView> targets = discoveryClient.discover(discoveryCaller()).stream()
                .filter(target -> target != null && caller.tenantId().equals(target.tenantId())
                        && target.connectorId() != null)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(SyncTargetView::connectorId, target -> target,
                                (first, ignored) -> first, LinkedHashMap::new),
                        values -> List.copyOf(values.values())));
        if (targets.isEmpty()) {
            throw new AuthorizationDeniedException("integration:dhb:product-sync-target-not-found");
        }
        if (targets.size() > 1) {
            throw new IllegalStateException("当前租户存在多个启用的订货宝商品主数据连接器，无法自动确定同步目标");
        }
        return targets.getFirst();
    }

    private static CallerIdentity requireCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null || caller.userId() == null) {
            throw new AuthorizationDeniedException("tenant-caller");
        }
        AuthorizationContext.requirePermission("erp:product:write");
        return caller;
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

    private static CallerIdentity discoveryCaller() {
        return serviceCaller(null, DISCOVERY_PERMISSIONS);
    }

    private static CallerIdentity tenantServiceCaller(UUID tenantId) {
        return serviceCaller(tenantId, DATA_PERMISSIONS);
    }

    private static CallerIdentity serviceCaller(UUID tenantId, Set<String> permissions) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ERP_PRODUCT_SYNC_SERVICE"), permissions);
    }

    private static final class Accumulator {
        private long fetched;
        private long created;
        private long changed;
        private long duplicates;
        private long rejected;
        private int pages;

        void add(ImportResult result) {
            created += result.created();
            changed += result.changed();
            duplicates += result.duplicates();
            rejected += result.rejected();
            fetched += result.created() + result.changed() + result.duplicates() + result.rejected();
        }

        RunStatistics statistics() {
            return new RunStatistics(fetched, created, changed, duplicates, rejected, pages);
        }
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
