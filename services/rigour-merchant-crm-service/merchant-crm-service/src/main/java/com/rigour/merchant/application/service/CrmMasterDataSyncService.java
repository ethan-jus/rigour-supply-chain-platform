package com.rigour.merchant.application.service;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.client.ConnectorSyncLeaseClient;
import com.rigour.integration.client.ConnectorSyncLeaseClient.LeaseGuard;
import com.rigour.integration.client.ExternalObjectMappingClient;
import com.rigour.merchant.api.v1.model.SyncCommand;
import com.rigour.merchant.api.v1.model.SyncObjectResult;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.merchant.application.port.out.CrmMasterDataStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.ImportResult;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.RunStatistics;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.Collected;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.application.port.out.DhbCrmSyncTargetDiscoveryClient;
import com.rigour.merchant.application.port.out.IamStaffDirectoryClient;
import com.rigour.merchant.application.port.out.IamStaffDirectoryClient.ResolvedStaff;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.sync.SyncConflictClassifier;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** CRM 手动和定时同步的统一编排用例。 */
@Service
public final class CrmMasterDataSyncService {
    private static final int IMPORT_BATCH_SIZE = 200;
    private static final int MAPPING_BATCH_SIZE = 200;
    static final String CONNECTOR_LEASE_CONFLICT = "CONNECTOR_LEASE_CONFLICT";
    static final String OBJECT_SYNC_ALREADY_RUNNING = "OBJECT_SYNC_ALREADY_RUNNING";
    static final String MULTIPLE_ACTIVE_CONNECTORS = "MULTIPLE_ACTIVE_CONNECTORS";
    static final String MULTIPLE_ACTIVE_SYNC_TASKS = "MULTIPLE_ACTIVE_SYNC_TASKS";
    private static final Logger log = LoggerFactory.getLogger(CrmMasterDataSyncService.class);
    public static final String IAM_STAFF_BY_SOURCE_ID = "_iamStaffBySourceId";
    private static final UUID SERVICE_ID = UUID.nameUUIDFromBytes(
            "service:rigour-merchant-crm-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> DISCOVERY_PERMISSIONS = Set.of("integration:dhb:sync-discovery");
    private static final Set<String> DATA_PERMISSIONS = Set.of("integration:dhb:read", "iam:staff:read");

    private final DhbCrmMasterDataClient client;
    private final DhbCrmSyncTargetDiscoveryClient discovery;
    private final CrmMasterDataStore store;
    private final CrmDictionaryCoverageService dictionaryCoverage;
    private final ConnectorSyncLeaseClient connectorLease;
    private final ExternalObjectMappingClient mappingClient;
    private final IamStaffDirectoryClient staffDirectory;

    public CrmMasterDataSyncService(DhbCrmMasterDataClient client,
                                    DhbCrmSyncTargetDiscoveryClient discovery,
                                    CrmMasterDataStore store,
                                    CrmDictionaryCoverageService dictionaryCoverage,
                                    ConnectorSyncLeaseClient connectorLease,
                                    ExternalObjectMappingClient mappingClient,
                                    IamStaffDirectoryClient staffDirectory) {
        this.client = client;
        this.discovery = discovery;
        this.store = store;
        this.dictionaryCoverage = dictionaryCoverage;
        this.connectorLease = connectorLease;
        this.mappingClient = mappingClient;
        this.staffDirectory = staffDirectory;
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

    public SyncResult runScheduled(CallerIdentity caller, UUID connectorId,
                                   UUID sourceTaskId, int maxPages) {
        requireScheduledCaller(caller);
        Objects.requireNonNull(sourceTaskId, "sourceTaskId不能为空");
        int pages = maxPages(maxPages);
        AtomicBoolean leaseAcquired = new AtomicBoolean();
        try {
            return connectorLease.executeWithLeaseGuard(caller.tenantId(), connectorId,
                    guard -> {
                        leaseAcquired.set(true);
                        return runBatchUnderLease(caller.tenantId(), connectorId, null,
                                sourceTaskId, CrmMasterDataObjectType.SYNC_ORDER,
                                pages, "SCHEDULED", guard);
                    });
        } catch (RuntimeException error) {
            if (leaseAcquired.get() || !SyncConflictClassifier.isAlreadyRunning(error)) throw error;
            return recordScheduledSkip(caller, connectorId, sourceTaskId, pages,
                    CONNECTOR_LEASE_CONFLICT, "订货宝连接器已有同步任务运行，本轮未执行");
        }
    }

    private SyncResult runBatch(UUID tenantId, UUID connectorId, UUID actorId,
                                List<CrmMasterDataObjectType> objectTypes,
                                int maxPages, String triggerType) {
        return connectorLease.executeWithLeaseGuard(tenantId, connectorId,
                guard -> runBatchUnderLease(tenantId, connectorId, actorId, null,
                        objectTypes, maxPages, triggerType, guard));
    }

    private SyncResult runBatchUnderLease(UUID tenantId, UUID connectorId, UUID actorId,
                                          UUID sourceTaskId,
                                          List<CrmMasterDataObjectType> objectTypes,
                                          int maxPages, String triggerType,
                                          LeaseGuard leaseGuard) {
        UUID batchId = UUID.randomUUID();
        List<SyncObjectResult> results = new ArrayList<>();
        for (CrmMasterDataObjectType objectType : objectTypes) {
            results.add(runObject(tenantId, connectorId, actorId, sourceTaskId,
                    objectType, maxPages, triggerType, leaseGuard));
        }
        boolean skipped = results.stream().anyMatch(item -> "SKIPPED".equals(item.status()));
        boolean completed = results.stream().anyMatch(item -> !"SKIPPED".equals(item.status()));
        String status = skipped && !completed ? "SKIPPED"
                : skipped || results.stream().anyMatch(item -> "SUCCEEDED_WITH_WARNINGS".equals(item.status()))
                ? "SUCCEEDED_WITH_WARNINGS" : "SUCCEEDED";
        return new SyncResult(batchId, status, results);
    }

    private SyncObjectResult runObject(UUID tenantId, UUID connectorId, UUID actorId,
                                       UUID sourceTaskId,
                                       CrmMasterDataObjectType objectType, int maxPages,
                                       String triggerType, LeaseGuard leaseGuard) {
        UUID runId;
        try {
            runId = store.startRun(tenantId, connectorId, actorId, sourceTaskId,
                    objectType, maxPages, triggerType);
        } catch (RuntimeException error) {
            if (!"SCHEDULED".equals(triggerType)
                    || !SyncConflictClassifier.isAlreadyRunning(error)) throw error;
            return skippedObject(tenantId, connectorId, sourceTaskId, objectType, maxPages,
                    OBJECT_SYNC_ALREADY_RUNNING, "相同对象范围已有同步任务运行，本轮未执行");
        }
        Accumulator counts = new Accumulator();
        try {
            Collected collected = client.collect(tenantServiceCaller(tenantId), connectorId,
                    objectType, maxPages);
            counts.pages = collected.pages();
            for (int begin = 0; begin < collected.items().size(); begin += IMPORT_BATCH_SIZE) {
                int end = Math.min(begin + IMPORT_BATCH_SIZE, collected.items().size());
                List<SourceRecord> batch = collected.items().subList(begin, end);
                if (objectType == CrmMasterDataObjectType.CUSTOMER) {
                    batch = enrichIamStaff(tenantId, connectorId, batch);
                }
                store.importRecords(tenantId, connectorId, runId, objectType,
                        batch).forEach(counts::add);
            }
            Audit dictionaryAudit = dictionaryCoverage.sync(tenantId, collected);
            int mappingAccepted = registerExternalObjectMappings(
                    tenantId, connectorId, runId, objectType);
            leaseGuard.ensureActive();
            RunStatistics statistics = store.completeRun(tenantId, connectorId, runId,
                    objectType, counts.statistics(), counts.rejected == 0);
            long unmapped = counts.unmapped + dictionaryAudit.unmapped();
            String status = unmapped == 0 ? "SUCCEEDED" : "SUCCEEDED_WITH_WARNINGS";
            log.info("CRM订货宝同步完成 tenantId={} connectorId={} objectType={} runId={} fetched={} created={} changed={} repaired={} duplicates={} absent={} rejected={} pages={} unmapped={} mappingAccepted={} dictionaryRevisions={}",
                    tenantId, connectorId, objectType, runId, statistics.fetched(),
                    statistics.created(), statistics.changed(), statistics.repaired(),
                    statistics.duplicates(), statistics.absent(), statistics.rejected(),
                    statistics.pages(), unmapped, mappingAccepted,
                    dictionaryAudit.revisions());
            return result(runId, objectType, status, statistics, unmapped, dictionaryAudit);
        } catch (RuntimeException error) {
            RunStatistics statistics = counts.statistics();
            store.failRun(tenantId, connectorId, runId, statistics, error);
            log.error("CRM订货宝同步失败 tenantId={} connectorId={} objectType={} runId={} reason={}",
                    tenantId, connectorId, objectType, runId, oneLine(error.getMessage()), error);
            throw error;
        }
    }

    private List<SourceRecord> enrichIamStaff(UUID tenantId, UUID connectorId,
                                              List<SourceRecord> records) {
        if (records == null || records.isEmpty()) return List.of();
        LinkedHashSet<String> sourceStaffIds = new LinkedHashSet<>();
        records.forEach(record -> collectStaffSourceIds(record.sourceFields(), sourceStaffIds));
        if (sourceStaffIds.isEmpty()) return records;
        List<ResolvedStaff> resolved = staffDirectory.resolveDinghuobaoStaff(
                tenantServiceCaller(tenantId), connectorId.toString(), List.copyOf(sourceStaffIds));
        Map<String, Map<String, Object>> bySourceId = new LinkedHashMap<>();
        for (ResolvedStaff staff : resolved) {
            String sourceStaffId = cleanStaffId(staff.sourceStaffId());
            if (sourceStaffId == null) continue;
            String staffCode = blankToNull(staff.staffCode());
            String staffName = blankToNull(staff.staffName());
            if (staffCode == null && staffName == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceStaffId", sourceStaffId);
            item.put("staffCode", staffCode);
            item.put("staffName", staffName);
            bySourceId.put(sourceStaffId, item);
        }
        if (bySourceId.isEmpty()) return records;
        return records.stream().map(record -> {
            Map<String, Object> fields = new LinkedHashMap<>(record.sourceFields());
            fields.put(IAM_STAFF_BY_SOURCE_ID, bySourceId);
            return new SourceRecord(record.sourceId(), record.sourceCode(), record.sourceName(),
                    record.sourceStatus(), record.sourceCreatedAt(), record.sourceUpdatedAt(), fields);
        }).toList();
    }

    private static void collectStaffSourceIds(Map<String, Object> fields, Set<String> result) {
        if (fields == null || fields.isEmpty()) return;
        staffValues(fields, "staffID", "staffId", "staff_id",
                "secondaryStaffID", "secondaryStaffId", "secondary_staff_id",
                "assistantStaffID", "assistantStaffId", "assistant_staff_id",
                "auxiliaryStaffID", "auxiliaryStaffId", "auxiliary_staff_id",
                "staffID2", "staffId2", "staff_id2", "staffID_2", "staff_id_2")
                .forEach(value -> {
                    String cleaned = cleanStaffId(value);
                    if (cleaned != null) result.add(cleaned);
                });
        for (String key : List.of("staffs", "staffList", "salesStaffs", "salesmen",
                "businessStaffs", "businessStaffList", "staff_info_list")) {
            Object raw = fields.get(key);
            if (!(raw instanceof Collection<?> collection)) continue;
            for (Object item : collection) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> nested = new LinkedHashMap<>();
                map.forEach((name, value) -> nested.put(String.valueOf(name), value));
                String sourceStaffId = blankToNull(firstValue(nested, "staffID", "staffId",
                        "staff_id", "sourceStaffId", "source_staff_id"));
                sourceStaffId = cleanStaffId(sourceStaffId);
                if (sourceStaffId != null) result.add(sourceStaffId);
            }
        }
    }

    private static List<String> staffValues(Map<String, Object> fields, String... keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            if (!fields.containsKey(key)) continue;
            Object raw = fields.get(key);
            if (raw instanceof Collection<?> collection) {
                collection.forEach(value -> addStaffTokens(result, value));
            } else addStaffTokens(result, raw);
        }
        return List.copyOf(result);
    }

    private static void addStaffTokens(List<String> values, Object raw) {
        String text = blankToNull(raw == null ? null : String.valueOf(raw));
        if (text == null) return;
        Arrays.stream(text.split("[,，;；|、\\n]"))
                .map(CrmMasterDataSyncService::blankToNull)
                .filter(Objects::nonNull)
                .forEach(values::add);
    }

    private static String firstValue(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            if (fields.containsKey(key)) return fields.get(key) == null ? null : String.valueOf(fields.get(key));
        }
        return null;
    }

    private static String cleanStaffId(String value) {
        String cleaned = blankToNull(value);
        return cleaned == null || "0".equals(cleaned) ? null : cleaned;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String cleaned = value.strip();
        return cleaned.isEmpty() || "null".equalsIgnoreCase(cleaned) ? null : cleaned;
    }

    private int registerExternalObjectMappings(UUID tenantId, UUID connectorId, UUID runId,
                                               CrmMasterDataObjectType objectType) {
        var mappings = store.externalObjectMappings(tenantId, connectorId, runId, objectType);
        if (mappings.isEmpty()) return 0;
        int accepted = 0;
        for (int begin = 0; begin < mappings.size(); begin += MAPPING_BATCH_SIZE) {
            int end = Math.min(begin + MAPPING_BATCH_SIZE, mappings.size());
            var result = mappingClient.upsert(tenantId, mappings.subList(begin, end));
            accepted += result == null ? 0 : result.accepted();
        }
        return accepted;
    }

    SyncResult recordScheduledSkip(CallerIdentity caller, UUID connectorId,
                                   UUID sourceTaskId, int maxPages,
                                   String reasonCode, String reasonMessage) {
        requireScheduledCaller(caller);
        Objects.requireNonNull(sourceTaskId, "sourceTaskId不能为空");
        int pages = maxPages(maxPages);
        List<UUID> runIds;
        try {
            runIds = store.recordSkippedRuns(caller.tenantId(), connectorId, sourceTaskId,
                    CrmMasterDataObjectType.SYNC_ORDER, pages, reasonCode, reasonMessage);
        } catch (RuntimeException error) {
            throw new ScheduledSkipAuditException(error);
        }
        if (runIds.size() != CrmMasterDataObjectType.SYNC_ORDER.size()) {
            throw new ScheduledSkipAuditException(
                    new IllegalStateException("CRM跳过审计返回数量不完整"));
        }
        List<SyncObjectResult> results = new ArrayList<>(runIds.size());
        for (int index = 0; index < runIds.size(); index++) {
            results.add(skippedResult(runIds.get(index),
                    CrmMasterDataObjectType.SYNC_ORDER.get(index)));
        }
        log.info("CRM定时同步已记录跳过 tenantId={} connectorId={} reasonCode={} reason={}",
                caller.tenantId(), connectorId, reasonCode, oneLine(reasonMessage));
        return new SyncResult(UUID.randomUUID(), "SKIPPED", results);
    }

    private SyncObjectResult skippedObject(UUID tenantId, UUID connectorId, UUID sourceTaskId,
                                           CrmMasterDataObjectType objectType, int maxPages,
                                           String reasonCode, String reasonMessage) {
        try {
            UUID runId = store.recordSkippedRun(tenantId, connectorId, sourceTaskId,
                    objectType, maxPages, reasonCode, reasonMessage);
            return skippedResult(runId, objectType);
        } catch (RuntimeException error) {
            throw new ScheduledSkipAuditException(error);
        }
    }

    private static SyncObjectResult skippedResult(UUID runId,
                                                   CrmMasterDataObjectType objectType) {
        return new SyncObjectResult(runId, objectType.name(), "SKIPPED", 0, 0,
                0, 0, 0, 0, 0, 0, Instant.now(), 0, Map.of());
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
                                           String status, RunStatistics value, long unmapped,
                                           Audit audit) {
        return new SyncObjectResult(runId, type.name(), status, value.fetched(), value.created(),
                value.changed(), value.repaired(), value.duplicates(), value.absent(),
                value.rejected(), value.pages(), Instant.now(), unmapped, audit.revisions());
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

    static final class ScheduledSkipAuditException extends RuntimeException {
        ScheduledSkipAuditException(RuntimeException cause) {
            super("CRM定时同步跳过审计持久化失败", cause);
        }
    }

    private static final class Accumulator {
        long fetched;
        long created;
        long changed;
        long repaired;
        long duplicates;
        long absent;
        long rejected;
        long unmapped;
        int pages;

        void add(ImportResult result) {
            created += result.created();
            changed += result.changed();
            repaired += result.repaired();
            duplicates += result.duplicates();
            rejected += result.rejected();
            unmapped += result.unmapped();
            fetched++;
        }

        RunStatistics statistics() {
            return new RunStatistics(fetched, created, changed, repaired,
                    duplicates, absent, rejected, pages);
        }
    }
}
