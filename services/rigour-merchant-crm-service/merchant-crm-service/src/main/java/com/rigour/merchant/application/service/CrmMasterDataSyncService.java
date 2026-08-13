package com.rigour.merchant.application.service;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.merchant.api.v1.model.SyncCommand;
import com.rigour.merchant.api.v1.model.SyncObjectResult;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.merchant.application.port.out.CrmMasterDataStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.ImportResult;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.RunStatistics;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.Collected;
import com.rigour.merchant.application.port.out.DhbCrmSyncTargetDiscoveryClient;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** CRM 手动和定时同步的统一编排用例。 */
@Service
public final class CrmMasterDataSyncService {
    private static final int IMPORT_BATCH_SIZE = 200;
    private static final Logger log = LoggerFactory.getLogger(CrmMasterDataSyncService.class);
    private static final UUID SERVICE_ID = UUID.nameUUIDFromBytes(
            "service:rigour-merchant-crm-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> DISCOVERY_PERMISSIONS = Set.of("integration:dhb:sync-discovery");
    private static final Set<String> DATA_PERMISSIONS = Set.of("integration:dhb:read");

    private final DhbCrmMasterDataClient client;
    private final DhbCrmSyncTargetDiscoveryClient discovery;
    private final CrmMasterDataStore store;

    public CrmMasterDataSyncService(DhbCrmMasterDataClient client,
                                    DhbCrmSyncTargetDiscoveryClient discovery,
                                    CrmMasterDataStore store) {
        this.client = client;
        this.discovery = discovery;
        this.store = store;
    }

    public SyncResult run(SyncCommand command) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null || caller.userId() == null) {
            throw new AuthorizationDeniedException("tenant-user-caller");
        }
        AuthorizationContext.requirePermission("crm:customer:write");
        int maxPages = maxPages(command == null ? null : command.maxPages());
        CrmMasterDataObjectType selected = CrmMasterDataObjectType.parse(
                command == null ? null : command.objectType());
        SyncTargetView target = uniqueTarget(caller.tenantId());
        return runBatch(caller.tenantId(), target.connectorId(), caller.userId(),
                selected == null ? CrmMasterDataObjectType.SYNC_ORDER : List.of(selected),
                maxPages, "MANUAL");
    }

    public SyncResult runScheduled(CallerIdentity caller, UUID connectorId, int maxPages) {
        requireScheduledCaller(caller);
        return runBatch(caller.tenantId(), connectorId, null,
                CrmMasterDataObjectType.SYNC_ORDER, maxPages(maxPages), "SCHEDULED");
    }

    private SyncResult runBatch(UUID tenantId, UUID connectorId, UUID actorId,
                                List<CrmMasterDataObjectType> objectTypes,
                                int maxPages, String triggerType) {
        UUID batchId = UUID.randomUUID();
        List<SyncObjectResult> results = new ArrayList<>();
        for (CrmMasterDataObjectType objectType : objectTypes) {
            results.add(runObject(tenantId, connectorId, actorId, objectType, maxPages, triggerType));
        }
        return new SyncResult(batchId, "SUCCEEDED", results);
    }

    private SyncObjectResult runObject(UUID tenantId, UUID connectorId, UUID actorId,
                                       CrmMasterDataObjectType objectType, int maxPages,
                                       String triggerType) {
        UUID runId = store.startRun(tenantId, connectorId, actorId, objectType,
                maxPages, triggerType);
        Accumulator counts = new Accumulator();
        try {
            Collected collected = client.collect(tenantServiceCaller(tenantId), connectorId,
                    objectType, maxPages);
            counts.pages = collected.pages();
            for (int begin = 0; begin < collected.items().size(); begin += IMPORT_BATCH_SIZE) {
                int end = Math.min(begin + IMPORT_BATCH_SIZE, collected.items().size());
                store.importRecords(tenantId, connectorId, runId, objectType,
                        collected.items().subList(begin, end)).forEach(counts::add);
            }
            RunStatistics statistics = store.completeRun(tenantId, connectorId, runId,
                    objectType, counts.statistics(), counts.rejected == 0);
            log.info("CRM订货宝同步完成 tenantId={} connectorId={} objectType={} runId={} fetched={} created={} changed={} repaired={} duplicates={} absent={} rejected={} pages={}",
                    tenantId, connectorId, objectType, runId, statistics.fetched(),
                    statistics.created(), statistics.changed(), statistics.repaired(),
                    statistics.duplicates(), statistics.absent(), statistics.rejected(),
                    statistics.pages());
            return result(runId, objectType, "SUCCEEDED", statistics);
        } catch (RuntimeException error) {
            RunStatistics statistics = counts.statistics();
            store.failRun(tenantId, connectorId, runId, statistics, error);
            log.error("CRM订货宝同步失败 tenantId={} connectorId={} objectType={} runId={} reason={}",
                    tenantId, connectorId, objectType, runId, oneLine(error.getMessage()), error);
            throw error;
        }
    }

    private SyncTargetView uniqueTarget(UUID tenantId) {
        List<SyncTargetView> values = discovery.discover(discoveryCaller()).stream()
                .filter(target -> target != null && tenantId.equals(target.tenantId())
                        && target.connectorId() != null)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(SyncTargetView::connectorId, value -> value,
                                (first, ignored) -> first, LinkedHashMap::new),
                        map -> List.copyOf(map.values())));
        if (values.isEmpty()) {
            throw new AuthorizationDeniedException("integration:dhb:crm-sync-target-not-found");
        }
        if (values.size() > 1) {
            throw new IllegalStateException("当前租户存在多个启用的订货宝CRM连接器，无法自动确定同步目标");
        }
        return values.getFirst();
    }

    private static SyncObjectResult result(UUID runId, CrmMasterDataObjectType type,
                                           String status, RunStatistics value) {
        return new SyncObjectResult(runId, type.name(), status, value.fetched(), value.created(),
                value.changed(), value.repaired(), value.duplicates(), value.absent(),
                value.rejected(), value.pages(), Instant.now());
    }

    private static int maxPages(Integer value) {
        int result = value == null ? 100 : value;
        if (result < 1 || result > 100) {
            throw new IllegalArgumentException("maxPages必须在1到100之间");
        }
        return result;
    }

    private static void requireScheduledCaller(CallerIdentity caller) {
        if (caller == null || caller.tenantId() == null || caller.userId() != null
                || !"SERVICE".equals(caller.principalScope())
                || (!caller.permissions().contains("integration:dhb:read")
                && !caller.permissions().contains("*:*:*"))) {
            throw new AuthorizationDeniedException("tenant-service-caller");
        }
    }

    public static CallerIdentity discoveryCaller() {
        return serviceCaller(null, DISCOVERY_PERMISSIONS);
    }

    public static CallerIdentity tenantServiceCaller(UUID tenantId) {
        return serviceCaller(tenantId, DATA_PERMISSIONS);
    }

    private static CallerIdentity serviceCaller(UUID tenantId, Set<String> permissions) {
        return new CallerIdentity("SERVICE", SERVICE_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("CRM_SYNC_SERVICE"), permissions);
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static final class Accumulator {
        long fetched;
        long created;
        long changed;
        long repaired;
        long duplicates;
        long absent;
        long rejected;
        int pages;

        void add(ImportResult result) {
            created += result.created();
            changed += result.changed();
            repaired += result.repaired();
            duplicates += result.duplicates();
            rejected += result.rejected();
            fetched++;
        }

        RunStatistics statistics() {
            return new RunStatistics(fetched, created, changed, repaired,
                    duplicates, absent, rejected, pages);
        }
    }
}
