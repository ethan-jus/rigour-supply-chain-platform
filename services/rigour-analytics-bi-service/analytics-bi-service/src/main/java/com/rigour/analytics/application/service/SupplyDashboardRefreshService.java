package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshRunView;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RefreshRun;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SourceRefreshResult;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
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
    private static final SourceMeta RECONCILIATION = new SourceMeta("BI_RECONCILIATION_CURRENT", "对账快照");

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
        CallerIdentity actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        return view(refreshTenant(actor.tenantId().toString(), JOB_MANUAL));
    }

    @Scheduled(
            fixedDelayString = "${rigour.analytics.supply-dashboard.refresh.fixed-delay-ms:3600000}",
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
            SourceRefreshResult customer = refreshIncremental(run.id(), tenantId, CUSTOMER, upperBound,
                    (from, to, syncedAt) -> store.refreshCustomerDim(tenantId, from, to, syncedAt));
            SourceRefreshResult order = refreshIncremental(run.id(), tenantId, ORDER, upperBound,
                    (from, to, syncedAt) -> store.refreshSalesOrderFact(tenantId, from, to, syncedAt));
            SourceRefreshResult orderLine = refreshIncremental(run.id(), tenantId, ORDER_LINE, upperBound,
                    (from, to, syncedAt) -> store.refreshSalesOrderLineFact(tenantId, from, to, syncedAt));
            SourceRefreshResult product = refreshIncremental(run.id(), tenantId, PRODUCT, upperBound,
                    (from, to, syncedAt) -> store.refreshProductDim(tenantId, from, to, syncedAt));
            SourceRefreshResult payment = refreshIncremental(run.id(), tenantId, PAYMENT, upperBound,
                    (from, to, syncedAt) -> store.refreshSalesPaymentFact(tenantId, from, to, syncedAt));
            long regionBackfillCount = store.backfillCustomerRegionAttribution(tenantId, Instant.now(clock));
            SourceRefreshResult inventory = refreshSnapshot(run.id(), tenantId, INVENTORY,
                    syncedAt -> store.refreshInventoryBalanceCurrent(tenantId, syncedAt));
            SourceRefreshResult reconciliation = refreshSnapshot(run.id(), tenantId, RECONCILIATION,
                    syncedAt -> store.refreshReconciliationCurrent(tenantId, monthStart(upperBound), upperBound, syncedAt));
            upserted += regionBackfillCount;
            for (SourceRefreshResult item : List.of(customer, order, orderLine, product, payment, inventory, reconciliation)) {
                pulled += item.pulledCount();
                upserted += item.upsertedCount();
                skipped += item.skippedCount();
                if (item.watermarkTime() != null && (watermark == null || item.watermarkTime().isAfter(watermark))) {
                    watermark = item.watermarkTime();
                }
            }
            Instant completedAt = Instant.now(clock);
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
            Long runId, String tenantId, SourceMeta source, Instant upperBound, IncrementalRefresh refresh) {
        Instant from = store.checkpointWatermark(tenantId, source.code())
                .map(value -> value.minus(lookback))
                .orElse(Instant.EPOCH);
        Instant syncedAt = Instant.now(clock);
        SourceRefreshResult result = refresh.execute(from, upperBound, syncedAt);
        Instant nextWatermark = result.watermarkTime() == null ? upperBound : result.watermarkTime();
        store.updateCheckpoint(tenantId, source.code(), source.name(), nextWatermark, syncedAt, runId);
        return result;
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

    private static Instant monthStart(Instant instant) {
        LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private record SourceMeta(String code, String name) {
    }

    @FunctionalInterface
    private interface IncrementalRefresh {
        SourceRefreshResult execute(Instant from, Instant to, Instant syncedAt);
    }

    @FunctionalInterface
    private interface SnapshotRefresh {
        SourceRefreshResult execute(Instant syncedAt);
    }
}
