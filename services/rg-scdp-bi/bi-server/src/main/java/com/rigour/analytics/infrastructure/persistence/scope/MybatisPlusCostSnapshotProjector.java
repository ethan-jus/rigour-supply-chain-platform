package com.rigour.analytics.infrastructure.persistence.scope;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.analytics.application.port.out.*;
import com.rigour.analytics.domain.cost.CostFact;
import com.rigour.analytics.infrastructure.persistence.entity.*;
import com.rigour.analytics.infrastructure.persistence.mapper.*;

import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 源版本前后校验、费用和组织映射一起发布；事务失败不会替换上一完整批次。 */
@Component
public class MybatisPlusCostSnapshotProjector implements CostDashboardRefresh {
    // Changing the classification contract must rebuild persisted facts even when source data is
    // unchanged.
    private static final String NORMALIZATION_VERSION_KEY = "BI_COST_NORMALIZATION";
    private static final String NORMALIZATION_VERSION = "6";
    private static final Logger log =
            LoggerFactory.getLogger(MybatisPlusCostSnapshotProjector.class);
    private final CostSnapshotMapper snapshots;
    private final CostFactMapper facts;
    private final CostRefreshStateMapper states;
    private final BiSourceSnapshotClient client;
    private final CostSourceNormalizer normalizer;
    private final ObjectMapper json;
    private final Clock clock;
    // 仅并发无状态的源版本 HTTP 读取；数据库事务、源分页、关联核对仍在当前线程执行。
    private final ThreadPoolExecutor versionReads =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    task -> {
                        var thread = new Thread(task, "cost-source-version");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public MybatisPlusCostSnapshotProjector(
            CostSnapshotMapper snapshots,
            CostFactMapper facts,
            CostRefreshStateMapper states,
            BiSourceSnapshotClient client,
            CostSourceNormalizer normalizer,
            ObjectMapper json,
            Clock analyticsClock) {
        this.snapshots = snapshots;
        this.facts = facts;
        this.states = states;
        this.client = client;
        this.normalizer = normalizer;
        this.json = json;
        this.clock = analyticsClock;
    }

    @Transactional(timeout = 240)
    public void refresh(String tenant) {
        long started = System.nanoTime();
        UUID tenantId = UUID.fromString(tenant);
        states.initialize(tenant);
        var state = states.lock(tenant);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var current =
                state.getSnapshotId() == null
                        ? null
                        : snapshots.selectOne(
                                new QueryWrapper<CostSnapshotEntity>()
                                        .first("/* supply-scoped */")
                                        .eq("tenant_id", tenant)
                                        .eq("id", state.getSnapshotId()));
        // Never throttle before checking source versions: a confirmation immediately after a
        // successful refresh must be visible. The tenant lock coalesces concurrent rebuilds.
        Map<String, String> versions = readVersions(tenantId);
        versions.put(NORMALIZATION_VERSION_KEY, NORMALIZATION_VERSION);
        // 源版本、归类口径未变且业务截止日未跨天时复用完整批次。
        if (current != null
                && current.getEffectiveCutoff()
                        .equals(clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
                && current.getGeneratedAt().isAfter(now.minusHours(23))
                && json.readTree(current.getVersionsJson()).equals(json.valueToTree(versions))) {
            states.update(
                    null,
                    new UpdateWrapper<CostRefreshStateEntity>()
                            .eq("tenant_id", tenant)
                            .set("status", "SUCCESS")
                            .set("error_code", null)
                            .set("attempted_at", now)
                            .set("succeeded_at", now));
            log.info(
                    "cost_snapshot_unchanged tenant={} snapshot={} elapsedMs={}",
                    tenant,
                    current.getId(),
                    (System.nanoTime() - started) / 1_000_000);
            return;
        }
        var departments =
                readReference(tenantId, "HR", "HR_DEPARTMENT", versions.get("HR_DEPARTMENT"));
        var employees = readReference(tenantId, "HR", "HR_EMPLOYEE", versions.get("HR_EMPLOYEE"));
        var areas =
                readReference(
                        tenantId, "CRM", "CRM_CUSTOMER_AREA", versions.get("CRM_CUSTOMER_AREA"));
        var cities = normalizer.cities(departments, areas);
        Map<String, List<String>> paths = new HashMap<>();
        for (var d : departments) paths.put(d.get("id"), normalizer.path(d.get("department_path")));
        Map<String, Map<String, String>> employeeIndex = new HashMap<>();
        for (var e : employees) employeeIndex.put(e.get("employee_code"), e);
        String id = UUID.randomUUID().toString();
        long count = 0;
        Map<String, CostFact> originals = new HashMap<>();
        for (var d : CostSourceDatasets.ALL) {
            String cursor = "";
            int pages = 0;
            long sourceRows = 0;
            while (true) {
                if (++pages > 1000) throw new IllegalStateException("费用源超过100万笔容量，请调整容量配置与验收");
                var page = client.page(tenantId, d.source(), d.code(), cursor);
                requireVersion(versions.get(d.code()), page.version());
                List<CostFactEntity> batch = new ArrayList<>();
                if (page.items().size() > 1000) throw new IllegalStateException("费用源分页超限");
                for (var source : page.items()) {
                    if (!new HashSet<>(d.columns()).equals(source.keySet())
                            || !tenant.equals(source.get("tenant_id")))
                        throw new IllegalStateException("费用源字段或租户不匹配");
                    cursor = CostSourceDatasets.validateCursor(source.get("id"), cursor);
                    var fact = normalizer.normalize(d.source(), source, cities, paths);
                    if (fact == null) continue;
                    if (!"HR".equals(fact.source())) originals.put(fact.key(), fact);
                    else fact = CostReimbursementReconciler.reconcile(fact, originals);
                    // 营销员工的当前部门用于源模块同义权限核验；同一批次带入现行员工归属。
                    if ("marketing".equals(fact.category()) && fact.employeeCode() != null) {
                        var employee = employeeIndex.get(fact.employeeCode());
                        if (employee != null && !"1".equals(employee.get("deleted"))) {
                            Map<String, CostFact.Dimension> dimensions =
                                    new LinkedHashMap<>(fact.dimensions());
                            dimensions.put(
                                    "authorizationEmployeeDepartment",
                                    new CostFact.Dimension(
                                            Objects.toString(
                                                    employee.get("department_id"), "UNKNOWN"),
                                            Objects.toString(employee.get("department_path"), "[]"),
                                            false));
                            fact =
                                    new CostFact(
                                            fact.source(),
                                            fact.sourceId(),
                                            fact.sourceTable(),
                                            fact.documentNo(),
                                            fact.title(),
                                            fact.category(),
                                            fact.sourceCategory(),
                                            fact.city(),
                                            fact.cityName(),
                                            fact.rawCity(),
                                            fact.mappingState(),
                                            fact.date(),
                                            fact.amount(),
                                            fact.status(),
                                            fact.payment(),
                                            fact.sourceSystem(),
                                            fact.employeeCode(),
                                            fact.employeeName(),
                                            fact.departmentId(),
                                            fact.departmentPath(),
                                            fact.warehouseId(),
                                            fact.sourceWarehouseId(),
                                            fact.createdBy(),
                                            fact.revision(),
                                            fact.remark(),
                                            Map.copyOf(dimensions),
                                            fact.originalNumbers());
                        }
                    }
                    var row = new CostFactEntity();
                    row.setId(UUID.randomUUID().toString());
                    row.setTenantId(tenant);
                    row.setSnapshotId(id);
                    row.setSourceService(d.source());
                    row.setSourceId(fact.sourceId());
                    row.setCostDate(fact.date());
                    row.setCategory(fact.category());
                    row.setRecordJson(json.writeValueAsString(fact));
                    batch.add(row);
                }
                if (!batch.isEmpty()) facts.insertBatch(batch);
                count += batch.size();
                sourceRows += page.items().size();
                if (page.items().size() < 1000) break;
            }
            log.info(
                    "cost_snapshot_source tenant={} snapshot={} dataset={} version={} rows={}"
                            + " pages={}",
                    tenant,
                    id,
                    d.code(),
                    versions.get(d.code()),
                    sourceRows,
                    pages);
        }
        var finalVersions = readVersions(tenantId);
        for (var e : versions.entrySet()) {
            if (NORMALIZATION_VERSION_KEY.equals(e.getKey())) continue;
            requireVersion(e.getValue(), finalVersions.get(e.getKey()));
        }
        var snapshot = new CostSnapshotEntity();
        snapshot.setId(id);
        snapshot.setTenantId(tenant);
        snapshot.setGeneratedAt(now);
        snapshot.setEffectiveCutoff(
                clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
        snapshot.setVersionsJson(json.writeValueAsString(versions));
        snapshot.setCitiesJson(json.writeValueAsString(cities));
        snapshot.setRowCount(count);
        snapshots.insert(snapshot);
        states.update(
                null,
                new UpdateWrapper<CostRefreshStateEntity>()
                        .eq("tenant_id", tenant)
                        .set("snapshot_id", id)
                        .set("status", "SUCCESS")
                        .set("error_code", null)
                        .set("attempted_at", now)
                        .set("succeeded_at", now));
        // 只清理过期已发布批次，在途查询统一返回SNAPSHOT_EXPIRED；不删除最近成功的当前批次。
        var old =
                snapshots.selectList(
                        new QueryWrapper<CostSnapshotEntity>()
                                .first("/* supply-scoped */")
                                .eq("tenant_id", tenant)
                                .lt("generated_at", now.minusHours(24))
                                .ne("id", id));
        for (var s : old) {
            facts.delete(
                    new QueryWrapper<CostFactEntity>()
                            .eq("tenant_id", tenant)
                            .eq("snapshot_id", s.getId()));
            snapshots.delete(
                    new QueryWrapper<CostSnapshotEntity>()
                            .eq("tenant_id", tenant)
                            .eq("id", s.getId()));
        }
        log.info(
                "cost_snapshot_validated tenant={} snapshot={} rows={} elapsedMs={}",
                tenant,
                id,
                count,
                (System.nanoTime() - started) / 1_000_000);
    }

    @Transactional
    public void failed(String tenant) {
        states.initialize(tenant);
        states.update(
                null,
                new UpdateWrapper<CostRefreshStateEntity>()
                        .eq("tenant_id", tenant)
                        .set("status", "FAILED")
                        .set("error_code", "SOURCE_READ_FAILED")
                        .set(
                                "attempted_at",
                                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private List<Map<String, String>> readReference(
            UUID tenant, String source, String dataset, String version) {
        var d =
                BiSourceDatasets.ALL.stream()
                        .filter(x -> dataset.equals(x.code()))
                        .findFirst()
                        .orElseThrow();
        Set<String> expectedColumns =
                new HashSet<>(d.columns().stream().map(BiSourceDatasets.Column::name).toList());
        String expectedTenant =
                d.binaryTenant() ? tenant.toString().replace("-", "") : tenant.toString();
        List<Map<String, String>> result = new ArrayList<>();
        String cursor = "";
        for (int i = 0; i < 1000; i++) {
            var page = client.page(tenant, source, dataset, cursor);
            requireVersion(version, page.version());
            if (page.items().size() > 1000) throw new IllegalStateException("组织源分页超限");
            for (var row : page.items()) {
                if (!expectedColumns.equals(row.keySet())
                        || !expectedTenant.equalsIgnoreCase(row.get("tenant_id")))
                    throw new IllegalStateException("组织源字段或租户不匹配");
                String next = row.get("id");
                if (next == null
                        || !next.matches(d.binaryKey() ? "[0-9a-fA-F]{32}" : "[0-9]{1,20}"))
                    throw new IllegalStateException("组织源游标无效");
                if (!cursor.isEmpty()
                        && (d.binaryKey()
                                ? next.compareToIgnoreCase(cursor) <= 0
                                : new java.math.BigInteger(next)
                                                .compareTo(new java.math.BigInteger(cursor))
                                        <= 0)) throw new IllegalStateException("组织源游标未前进");
                cursor = next;
                result.add(row);
            }
            if (page.items().size() < 1000) return result;
        }
        throw new IllegalStateException("组织源超过容量");
    }

    private static void requireVersion(String expected, String actual) {
        if (!Objects.equals(expected, actual))
            throw new IllegalStateException("SOURCE_VERSION_CHANGED");
    }

    private Map<String, String> readVersions(UUID tenant) {
        long started = System.nanoTime();
        Map<String, Future<String>> requests = new LinkedHashMap<>();
        try {
            var datasets =
                    new ArrayList<>(
                            CostSourceDatasets.ALL.stream()
                                    .map(CostSourceDatasets.Dataset::code)
                                    .toList());
            datasets.addAll(List.of("HR_DEPARTMENT", "HR_EMPLOYEE", "CRM_CUSTOMER_AREA"));
            for (String dataset : datasets)
                requests.put(
                        dataset,
                        versionReads.submit(
                                () ->
                                        client.version(
                                                tenant,
                                                dataset.substring(0, dataset.indexOf('_')),
                                                dataset)));
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : requests.entrySet()) {
                long remaining = TimeUnit.SECONDS.toNanos(40) - (System.nanoTime() - started);
                if (remaining <= 0) throw new TimeoutException();
                String version = entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
                if (version == null || version.isBlank()) throw new IllegalStateException("源版本缺失");
                result.put(entry.getKey(), version);
            }
            log.info(
                    "cost_source_versions tenant={} datasets={} elapsedMs={}",
                    tenant,
                    result.size(),
                    (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("源版本读取被中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("源版本读取失败", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("源版本读取超时", e);
        } finally {
            requests.values()
                    .forEach(
                            request -> {
                                if (!request.isDone()) request.cancel(true);
                            });
            versionReads.purge();
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        versionReads.shutdownNow();
    }
}
