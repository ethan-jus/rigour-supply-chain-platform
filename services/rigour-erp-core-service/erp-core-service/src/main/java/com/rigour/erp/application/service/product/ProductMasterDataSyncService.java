package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.model.DictionaryMappingAudit;
import com.rigour.erp.application.port.out.DhbProductMasterDataClient;
import com.rigour.erp.application.port.out.DhbProductMasterDataClient.Collected;
import com.rigour.erp.application.port.out.DhbProductSyncTargetDiscoveryClient;
import com.rigour.erp.application.port.out.ProductMasterDataStore;
import com.rigour.erp.application.port.out.ProductMasterDataStore.ImportResult;
import com.rigour.erp.application.port.out.ProductMasterDataStore.RunStatistics;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.client.ConnectorSyncLeaseClient;
import com.rigour.integration.client.ConnectorSyncLeaseClient.LeaseGuard;
import com.rigour.integration.client.ExternalObjectMappingClient;
import com.rigour.erp.application.service.sync.BusinessDictionaryCoverageService;
import com.rigour.erp.application.service.sync.ErpScheduledSyncSkipException;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.sync.SyncConflictClassifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int HEARTBEAT_INTERVAL_RECORDS = 50;

    private final DhbProductMasterDataClient client;
    private final DhbProductSyncTargetDiscoveryClient discoveryClient;
    private final ProductMasterDataStore store;
    private final BusinessDictionaryCoverageService dictionaryCoverage;
    private final ConnectorSyncLeaseClient connectorLease;
    private final ExternalObjectMappingClient mappingClient;

    public ProductMasterDataSyncService(DhbProductMasterDataClient client,
                                        DhbProductSyncTargetDiscoveryClient discoveryClient,
                                        ProductMasterDataStore store,
                                        BusinessDictionaryCoverageService dictionaryCoverage,
                                        ConnectorSyncLeaseClient connectorLease,
                                        ExternalObjectMappingClient mappingClient) {
        this.client = client;
        this.discoveryClient = discoveryClient;
        this.store = store;
        this.dictionaryCoverage = dictionaryCoverage;
        this.connectorLease = connectorLease;
        this.mappingClient = mappingClient;
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
        AtomicBoolean actionStarted = new AtomicBoolean(false);
        SyncAttempt attempt = new SyncAttempt();
        try {
            return connectorLease.executeWithLeaseGuard(caller.tenantId(), connectorId, guard -> {
                actionStarted.set(true);
                return runUnderLease(caller, connectorId, actorId, objectType, maxPages,
                        scheduled, attempt, guard);
            });
        } catch (RuntimeException error) {
            if (scheduled && !actionStarted.get() && SyncConflictClassifier.isAlreadyRunning(error)) {
                throw ErpScheduledSyncSkipException.connectorLeaseConflict(objectType.name());
            }
            if (attempt.runId != null) {
                store.failRun(tenantId, attempt.runId, attempt.counts.statistics(), error);
                log.error("ERP商品主数据同步批次失败 tenantId={} objectType={} connectorId={} runId={} errorType={} reason={}",
                        tenantId, objectType, connectorId, attempt.runId,
                        error.getClass().getSimpleName(), oneLine(error.getMessage()), error);
            }
            throw error;
        }
    }

    private ErpDataSyncResult runUnderLease(CallerIdentity caller, UUID connectorId, UUID actorId,
                                             MasterDataObjectType objectType, int maxPages,
                                             boolean scheduled, SyncAttempt attempt, LeaseGuard guard) {
        String tenantId = caller.tenantId().toString();
        try {
            attempt.runId = scheduled
                    ? store.startScheduledRun(tenantId, connectorId, actorId, objectType, maxPages)
                    : store.startRun(tenantId, connectorId, actorId, objectType, maxPages);
        } catch (RuntimeException error) {
            if (scheduled && SyncConflictClassifier.isAlreadyRunning(error)) {
                throw ErpScheduledSyncSkipException.objectSyncLockConflict(objectType.name());
            }
            throw error;
        }
        log.info("ERP商品主数据同步批次已创建 tenantId={} userId={} objectType={} connectorId={} runId={} maxPages={}",
                tenantId, actorId, objectType, connectorId, attempt.runId, maxPages);
        Collected collected = client.collect(tenantServiceCaller(caller.tenantId()),
                connectorId, objectType, maxPages);
        store.heartbeatRun(tenantId, attempt.runId);
        attempt.counts.fetched = collected.total();
        attempt.counts.pages = collected.pages();
        attempt.counts.sourceDetails = sourceDetails(objectType, collected);
        importCollected(tenantId, attempt.runId, collected, attempt.counts);
        store.heartbeatRun(tenantId, attempt.runId);
        attempt.counts.dictionaryAudit = dictionaryCoverage.inspect(caller.tenantId(), collected);
        store.heartbeatRun(tenantId, attempt.runId);
        RunStatistics statistics = attempt.counts.statistics();
        int mappingAccepted = registerExternalObjectMappings(
                tenantId, connectorId, attempt.runId, objectType);
        guard.ensureActive();
        store.completeRunWithSourcePresence(tenantId, attempt.runId,
                attempt.counts.rejected == 0 ? seenSourceIds(objectType, collected) : Map.of(),
                statistics);
        log.info("ERP商品主数据同步批次完成 tenantId={} objectType={} connectorId={} runId={} fetched={} created={} changed={} duplicates={} rejected={} pages={} mappingAccepted={}",
                tenantId, objectType, connectorId, attempt.runId, statistics.fetched(),
                statistics.created(), statistics.changed(), statistics.duplicates(),
                statistics.rejected(), statistics.pages(), mappingAccepted);
        String status = statistics.dictionaryAudit().unmapped() == 0
                ? "SUCCEEDED" : "SUCCEEDED_WITH_WARNINGS";
        return new ErpDataSyncResult(attempt.runId, objectType.name(), status, connectorId,
                statistics.fetched(), statistics.created(), statistics.changed(),
                statistics.duplicates(), statistics.rejected(),
                statistics.dictionaryAudit().unmapped(), statistics.dictionaryAudit().revisions(),
                attempt.counts.sourceDetails, statistics.pages(), Instant.now());
    }

    private void importCollected(String tenantId, UUID runId, Collected collected, Accumulator counts) {
        int imported = 0;
        for (var item : collected.categories()) {
            counts.add(store.importCategory(tenantId, runId, item));
            imported = heartbeatEvery(tenantId, runId, imported);
        }
        for (var item : collected.brands()) {
            counts.add(store.importBrand(tenantId, runId, item));
            imported = heartbeatEvery(tenantId, runId, imported);
        }
        for (var item : collected.specifications()) {
            counts.add(store.importSpecification(tenantId, runId, item));
            imported = heartbeatEvery(tenantId, runId, imported);
        }
        for (var item : collected.tags()) {
            counts.add(store.importTag(tenantId, runId, item));
            imported = heartbeatEvery(tenantId, runId, imported);
        }
        for (var item : collected.products()) {
            counts.add(store.importProduct(tenantId, runId, item));
            imported = heartbeatEvery(tenantId, runId, imported);
        }
    }

    private int heartbeatEvery(String tenantId, UUID runId, int imported) {
        int next = imported + 1;
        if (next % HEARTBEAT_INTERVAL_RECORDS == 0) store.heartbeatRun(tenantId, runId);
        return next;
    }

    private int registerExternalObjectMappings(String tenantId, UUID connectorId, UUID runId,
                                               MasterDataObjectType objectType) {
        var mappings = store.externalObjectMappings(tenantId, connectorId, runId, objectType);
        if (mappings == null || mappings.isEmpty()) return 0;
        var result = mappingClient.upsert(UUID.fromString(tenantId), mappings);
        return result == null ? 0 : result.accepted();
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
        private Map<String, Long> sourceDetails = Map.of();
        private DictionaryMappingAudit dictionaryAudit = DictionaryMappingAudit.empty();

        void add(ImportResult result) {
            created += result.created();
            changed += result.changed();
            duplicates += result.duplicates();
            rejected += result.rejected();
        }

        RunStatistics statistics() {
            return new RunStatistics(fetched, created, changed, duplicates, rejected, pages,
                    dictionaryAudit);
        }
    }

    private static final class SyncAttempt {
        private UUID runId;
        private final Accumulator counts = new Accumulator();
    }

    private static Map<String, Set<String>> seenSourceIds(MasterDataObjectType objectType,
                                                           Collected collected) {
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        switch (objectType) {
            case PRODUCT_SPU -> {
                seen.put("PRODUCT_SPU", collected.products().stream()
                        .map(item -> item.sourceId()).collect(Collectors.toSet()));
                seen.put("PRODUCT_SKU", collected.products().stream().flatMap(item -> item.skus().stream())
                        .map(item -> item.sourceId()).collect(Collectors.toSet()));
            }
            case CATEGORY -> seen.put("CATEGORY", collected.categories().stream()
                    .map(item -> item.sourceId()).collect(Collectors.toSet()));
            case BRAND -> seen.put("BRAND", collected.brands().stream()
                    .map(item -> item.sourceId()).collect(Collectors.toSet()));
            case SPECIFICATION -> {
                seen.put("SPECIFICATION", collected.specifications().stream()
                        .map(item -> item.sourceId()).collect(Collectors.toSet()));
                seen.put("SPECIFICATION_VALUE", collected.specifications().stream()
                        .flatMap(item -> item.values().stream()).map(item -> item.sourceId())
                        .collect(Collectors.toSet()));
            }
            case TAG -> seen.put("TAG", collected.tags().stream()
                    .map(item -> item.sourceId()).collect(Collectors.toSet()));
        }
        return Map.copyOf(seen);
    }

    private static Map<String, Long> sourceDetails(MasterDataObjectType objectType,
                                                   Collected collected) {
        Map<String, Long> details = new LinkedHashMap<>();
        switch (objectType) {
            case PRODUCT_SPU -> {
                details.put("PRODUCT_SPU", collected.total());
                details.put("PRODUCT_SKU", collected.products().stream()
                        .mapToLong(product -> product.skus().size()).sum());
                details.put("SOURCE_DELETED_PRODUCT", collected.products().stream()
                        .filter(product -> "SOURCE_DELETED".equalsIgnoreCase(product.sourceLifecycle())).count());
            }
            case SPECIFICATION -> {
                details.put("SPECIFICATION", collected.total());
                details.put("SPECIFICATION_VALUE", collected.specifications().stream()
                        .mapToLong(specification -> specification.values().size()).sum());
            }
            default -> details.put(objectType.name(), collected.total());
        }
        return java.util.Collections.unmodifiableMap(details);
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
