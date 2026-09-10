package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshRunView;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RefreshRun;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SourceRefreshResult;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 供应链 BI 定时/手动刷新用例。 */
@Service
public final class SupplyDashboardRefreshService {
    private static final Logger log = LoggerFactory.getLogger(SupplyDashboardRefreshService.class);
    private static final String WRITE_PERMISSION = "analytics:refresh:write";
    private static final String LOCK_CODE = "SUPPLY_DASHBOARD_REFRESH_LOCK";
    private static final String JOB_MANUAL = "SUPPLY_DASHBOARD_MANUAL";
    private static final String JOB_HOURLY = "SUPPLY_DASHBOARD_HOURLY";
    private static final SourceMeta CUSTOMER = new SourceMeta("CRM_CUSTOMER", "客户/门店");
    private static final SourceMeta ORDER = new SourceMeta("ORDER_SALES_ORDER", "销售订单");
    private static final SourceMeta ORDER_LINE = new SourceMeta("ORDER_SALES_ORDER_LINE", "销售订单行");
    private static final SourceMeta PRODUCT = new SourceMeta("ERP_PRODUCT", "ERP商品");
    private static final SourceMeta PAYMENT = new SourceMeta("ORDER_PAYMENT_RECORD", "销售回款记录");
    private static final SourceMeta INVENTORY = new SourceMeta("ERP_STOCK_BALANCE", "库存余额");
    private static final SourceMeta INVENTORY_OPERATION = new SourceMeta("ERP_INVENTORY_OPERATION", "采购/发货流转");
    private static final SourceMeta RECONCILIATION = new SourceMeta("BI_RECONCILIATION_CURRENT", "对账快照");
    private static final List<SourceMeta> SOURCE_METAS = List.of(
            CUSTOMER, ORDER, ORDER_LINE, PRODUCT, PAYMENT, INVENTORY, INVENTORY_OPERATION, RECONCILIATION);

    private final SupplyDashboardStore store;
    private final Clock clock;
    private final boolean scheduledEnabled;
    private final Duration lookback;
    private final Duration lockTtl;

    public SupplyDashboardRefreshService(
            SupplyDashboardStore store,
            Clock analyticsClock,
            @Value("${rigour.analytics.supply-dashboard.refresh.enabled:true}") boolean scheduledEnabled,
            @Value("${rigour.analytics.supply-dashboard.refresh.lookback:PT2H}") Duration lookback,
            @Value("${rigour.analytics.supply-dashboard.refresh.lock-ttl:PT55M}") Duration lockTtl) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(analyticsClock, "analyticsClock");
        this.scheduledEnabled = scheduledEnabled;
        this.lookback = positive(lookback, Duration.ofHours(2), "lookback");
        this.lockTtl = positive(lockTtl, Duration.ofMinutes(55), "lockTtl");
    }

    public SupplyDashboardRefreshRunView refreshCurrentTenant() {
        return refreshCurrentTenant(null);
    }

    public SupplyDashboardRefreshRunView refreshCurrentTenant(SupplyDashboardRefreshCommand command) {
        CallerIdentity actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        return view(refreshTenant(actor.tenantId().toString(), JOB_MANUAL, RefreshSelection.from(command)));
    }

    @Scheduled(
            fixedDelayString = "${rigour.analytics.supply-dashboard.refresh.fixed-delay-ms:1800000}",
            initialDelayString = "${rigour.analytics.supply-dashboard.refresh.initial-delay-ms:60000}")
    public void refreshScheduledTenants() {
        if (!scheduledEnabled) return;
        List<String> tenantIds;
        try {
            tenantIds = store.refreshTenantIds();
        } catch (RuntimeException exception) {
            log.warn("供应链 BI 定时刷新读取租户失败: {}", exception.getMessage(), exception);
            return;
        }
        for (String tenantId : tenantIds) {
            try {
                refreshTenant(tenantId, JOB_HOURLY);
            } catch (RuntimeException exception) {
                log.warn("供应链 BI 定时刷新失败, tenantId={}: {}", tenantId, exception.getMessage(), exception);
            }
        }
    }

    RefreshRun refreshTenant(String tenantId, String jobCode) {
        return refreshTenant(tenantId, jobCode, RefreshSelection.all());
    }

    private RefreshRun refreshTenant(String tenantId, String jobCode, RefreshSelection selection) {
        Instant startedAt = Instant.now(clock);
        boolean locked = store.acquireRefreshLock(tenantId, LOCK_CODE, startedAt, startedAt.plus(lockTtl));
        if (!locked) {
            return new RefreshRun(null, jobCode, tenantId, "SKIPPED", startedAt, startedAt,
                    null, 0L, 0L, 1L, "同租户已有供应链 BI 刷新任务运行中");
        }
        RefreshRun run = store.createRefreshRun(tenantId, jobCode, startedAt);
        long pulled = 0L;
        long upserted = 0L;
        long skipped = 0L;
        Instant watermark = null;
        try {
            Instant upperBound = Instant.now(clock);
            List<SourceRefreshResult> results = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            if (selection.includes(CUSTOMER)) {
                collectRefreshResult(results, failures, tenantId, CUSTOMER, () ->
                        refreshIncremental(run.id(), tenantId, CUSTOMER, upperBound, selection.fullRefresh(),
                                (from, to, syncedAt) -> store.refreshCustomerDim(tenantId, from, to, syncedAt)));
            }
            if (selection.includes(ORDER)) {
                collectRefreshResult(results, failures, tenantId, ORDER, () ->
                        refreshIncremental(run.id(), tenantId, ORDER, upperBound, selection.fullRefresh(),
                                (from, to, syncedAt) -> store.refreshSalesOrderFact(tenantId, from, to, syncedAt)));
            }
            if (selection.includes(ORDER_LINE)) {
                collectRefreshResult(results, failures, tenantId, ORDER_LINE, () ->
                        refreshIncremental(run.id(), tenantId, ORDER_LINE, upperBound, selection.fullRefresh(),
                                (from, to, syncedAt) -> store.refreshSalesOrderLineFact(tenantId, from, to, syncedAt)));
            }
            if (selection.includes(PRODUCT)) {
                collectRefreshResult(results, failures, tenantId, PRODUCT, () ->
                        refreshIncremental(run.id(), tenantId, PRODUCT, upperBound, selection.fullRefresh(),
                                (from, to, syncedAt) -> store.refreshProductDim(tenantId, from, to, syncedAt)));
            }
            if (selection.includes(PAYMENT)) {
                collectRefreshResult(results, failures, tenantId, PAYMENT, () ->
                        refreshIncremental(run.id(), tenantId, PAYMENT, upperBound, selection.fullRefresh(),
                                (from, to, syncedAt) -> store.refreshSalesPaymentFact(tenantId, from, to, syncedAt)));
            }
            if (selection.refreshesCustomerAttribution()) {
                try {
                    upserted += store.backfillCustomerRegionAttribution(tenantId, Instant.now(clock));
                } catch (RuntimeException exception) {
                    failures.add(failureReason("客户归属回填", exception));
                    log.warn("供应链 BI 来源刷新失败 tenantId={} sourceCode={} sourceName={} reason={}",
                            tenantId, "CUSTOMER_ATTRIBUTION_BACKFILL", "客户归属回填",
                            failureReason(exception), exception);
                }
            }
            if (selection.includes(INVENTORY)) {
                collectRefreshResult(results, failures, tenantId, INVENTORY, () ->
                        refreshSnapshot(run.id(), tenantId, INVENTORY,
                                syncedAt -> store.refreshInventoryBalanceCurrent(tenantId, syncedAt)));
            }
            if (selection.includes(INVENTORY_OPERATION)) {
                collectRefreshResult(results, failures, tenantId, INVENTORY_OPERATION, () ->
                        refreshSnapshot(run.id(), tenantId, INVENTORY_OPERATION,
                                syncedAt -> store.refreshInventoryOperationFact(tenantId, syncedAt)));
            }
            if (selection.includes(RECONCILIATION)) {
                collectRefreshResult(results, failures, tenantId, RECONCILIATION, () ->
                        refreshSnapshot(run.id(), tenantId, RECONCILIATION,
                                syncedAt -> store.refreshReconciliationCurrent(
                                        tenantId, monthStart(upperBound), upperBound, syncedAt)));
            }
            for (SourceRefreshResult item : results) {
                pulled += item.pulledCount();
                upserted += item.upsertedCount();
                skipped += item.skippedCount();
                if (item.watermarkTime() != null && (watermark == null || item.watermarkTime().isAfter(watermark))) {
                    watermark = item.watermarkTime();
                }
            }
            Instant completedAt = Instant.now(clock);
            if (!failures.isEmpty()) {
                String reason = failureReason(failures);
                RefreshRun failed = store.failRefreshRun(run.id(), completedAt, pulled, upserted, skipped, reason);
                store.releaseRefreshLock(tenantId, LOCK_CODE, "FAILED", completedAt, reason);
                return failed;
            }
            RefreshRun completed = store.completeRefreshRun(
                    run.id(), completedAt, watermark, pulled, upserted, skipped);
            store.releaseRefreshLock(tenantId, LOCK_CODE, "SUCCESS", completedAt, null);
            return completed;
        } catch (RuntimeException exception) {
            Instant completedAt = Instant.now(clock);
            String reason = failureReason(exception);
            RefreshRun failed = store.failRefreshRun(run.id(), completedAt, pulled, upserted, skipped, reason);
            store.releaseRefreshLock(tenantId, LOCK_CODE, "FAILED", completedAt, reason);
            return failed;
        }
    }

    private SourceRefreshResult refreshIncremental(
            Long runId, String tenantId, SourceMeta source, Instant upperBound,
            boolean fullRefresh, IncrementalRefresh refresh) {
        boolean targetNeedsBackfill = !fullRefresh && store.refreshTargetNeedsBackfill(tenantId, source.code());
        if (targetNeedsBackfill) {
            log.info("供应链 BI 目标表缺口触发全量补齐 tenantId={} sourceCode={}", tenantId, source.code());
        }
        Instant from = fullRefresh || targetNeedsBackfill
                ? Instant.EPOCH
                : store.checkpointWatermark(tenantId, source.code())
                        .map(value -> value.minus(lookback))
                        .orElse(Instant.EPOCH);
        Instant syncedAt = Instant.now(clock);
        SourceRefreshResult result = refresh.execute(from, upperBound, syncedAt);
        Instant nextWatermark = result.watermarkTime() == null ? upperBound : result.watermarkTime();
        store.updateCheckpoint(tenantId, source.code(), source.name(), nextWatermark, syncedAt, runId);
        return result;
    }

    private void collectRefreshResult(List<SourceRefreshResult> results, List<String> failures,
                                      String tenantId, SourceMeta source, RefreshCall refresh) {
        try {
            results.add(refresh.execute());
        } catch (RuntimeException exception) {
            failures.add(failureReason(source.name(), exception));
            log.warn("供应链 BI 来源刷新失败 tenantId={} sourceCode={} sourceName={} reason={}",
                    tenantId, source.code(), source.name(), failureReason(exception), exception);
        }
    }

    private SourceRefreshResult refreshSnapshot(
            Long runId, String tenantId, SourceMeta source, SnapshotRefresh refresh) {
        Instant syncedAt = Instant.now(clock);
        SourceRefreshResult result = refresh.execute(syncedAt);
        Instant nextWatermark = result.watermarkTime() == null ? syncedAt : result.watermarkTime();
        store.updateCheckpoint(tenantId, source.code(), source.name(), nextWatermark, syncedAt, runId);
        return result;
    }

    static SupplyDashboardRefreshRunView view(RefreshRun run) {
        return new SupplyDashboardRefreshRunView(
                run.id(),
                run.jobCode(),
                run.tenantId(),
                run.statusCode(),
                run.startedTime(),
                run.completedTime(),
                run.watermarkTime(),
                run.pulledCount(),
                run.upsertedCount(),
                run.skippedCount(),
                run.failureReason());
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        Duration normalized = value == null ? fallback : value;
        if (normalized.isNegative() || normalized.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    private static String failureReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private static String failureReason(String sourceName, RuntimeException exception) {
        String reason = sourceName + "刷新失败：" + failureReason(exception);
        return reason.length() <= 300 ? reason : reason.substring(0, 300);
    }

    private static String failureReason(List<String> failures) {
        String reason = String.join("; ", failures);
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }

    private static Instant monthStart(Instant instant) {
        LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private record SourceMeta(String code, String name) {
    }

    private record RefreshSelection(Set<String> sourceCodes, boolean fullRefresh) {
        private static RefreshSelection all() {
            return new RefreshSelection(sourceCodeSet(SOURCE_METAS), false);
        }

        private static RefreshSelection from(SupplyDashboardRefreshCommand command) {
            if (command == null) return all();
            boolean fullRefresh = command.fullRefreshEnabled();
            if (command.sourceCodes().isEmpty()) {
                return new RefreshSelection(sourceCodeSet(SOURCE_METAS), fullRefresh);
            }
            Set<String> knownCodes = sourceCodeSet(SOURCE_METAS);
            Set<String> selected = new LinkedHashSet<>();
            for (String value : command.sourceCodes()) {
                String sourceCode = value == null ? "" : value.trim().toUpperCase();
                if (sourceCode.isBlank()) continue;
                if (!knownCodes.contains(sourceCode)) {
                    throw new BusinessException(
                            ErrorCode.BAD_REQUEST, "BI刷新范围无效：" + sourceCode, List.of());
                }
                selected.add(sourceCode);
            }
            if (selected.isEmpty()) return new RefreshSelection(sourceCodeSet(SOURCE_METAS), fullRefresh);
            return new RefreshSelection(Set.copyOf(selected), fullRefresh);
        }

        private boolean includes(SourceMeta source) {
            return sourceCodes.contains(source.code());
        }

        private boolean refreshesCustomerAttribution() {
            return includes(CUSTOMER) || includes(ORDER) || includes(ORDER_LINE) || includes(PAYMENT);
        }
    }

    private static Set<String> sourceCodeSet(List<SourceMeta> sources) {
        Set<String> result = new LinkedHashSet<>();
        for (SourceMeta source : sources) {
            result.add(source.code());
        }
        return result;
    }

    @FunctionalInterface
    private interface IncrementalRefresh {
        SourceRefreshResult execute(Instant from, Instant to, Instant syncedAt);
    }

    @FunctionalInterface
    private interface SnapshotRefresh {
        SourceRefreshResult execute(Instant syncedAt);
    }

    @FunctionalInterface
    private interface RefreshCall {
        SourceRefreshResult execute();
    }
}
