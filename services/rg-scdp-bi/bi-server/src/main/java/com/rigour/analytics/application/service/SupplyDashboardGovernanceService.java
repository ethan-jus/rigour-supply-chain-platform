package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustSourceView;
import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FilterOption;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FilterOptions;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TrustSource;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.rigour.analytics.application.model.BiBusinessTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 供应链 BI 数据可信、对账和飞书旧数据归档用例。 */
@Service
public final class SupplyDashboardGovernanceService {
    private static final String READ_PERMISSION = "analytics:dashboard:read";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern CHECKSUM = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Duration TRUST_WARN_DELAY = Duration.ofHours(2);
    private static final Duration TRUST_CRITICAL_DELAY = Duration.ofHours(6);
    private static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");
    private static final Map<String, String> TRUST_SOURCES = Map.of(
            "CRM_CUSTOMER", "客户/门店",
            "ERP_PRODUCT", "ERP商品",
            "ORDER_SALES_ORDER", "销售订单",
            "ORDER_SALES_ORDER_LINE", "销售订单行",
            "ORDER_PAYMENT_RECORD", "销售回款记录",
            "ERP_STOCK_BALANCE", "库存余额",
            "ERP_INVENTORY_OPERATION", "采购/发货流转",
            "BI_RECONCILIATION_CURRENT", "对账快照");

    private final SupplyDashboardStore store;
    private final Clock clock;

    public SupplyDashboardGovernanceService(SupplyDashboardStore store, Clock analyticsClock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(analyticsClock, "analyticsClock");
    }

    public SupplyDashboardDataTrustView trust() {
        CallerIdentity actor = readActor();
        Instant now = Instant.now(clock);
        SupplyDashboardStore.TrustData data = store.trust(actor.tenantId().toString());
        List<SupplyDashboardDataTrustSourceView> sources = trustSources(data.sources(), now);
        return new SupplyDashboardDataTrustView(
                now,
                overallTrustStatus(sources),
                overallTrustDescription(sources),
                data.latestRun() == null ? null : SupplyDashboardRefreshService.view(data.latestRun()),
                sources);
    }

    public SupplyDashboardFilterOptionsView filterOptions() {
        CallerIdentity actor = readActor();
        FilterOptions options = store.filterOptions(actor.tenantId().toString());
        return new SupplyDashboardFilterOptionsView(
                optionViews(options.regions()),
                optionViews(options.salesOwners()),
                optionViews(options.customerTypes()),
                optionViews(options.productCategories()),
                defaultSourceSystems(optionViews(options.sourceSystems())));
    }

    private static CallerIdentity readActor() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(READ_PERMISSION);
        return caller;
    }

    private static List<SupplyDashboardDataTrustSourceView> trustSources(List<TrustSource> rows, Instant now) {
        Map<String, TrustSource> rowByCode = new LinkedHashMap<>();
        for (TrustSource row : rows) rowByCode.put(row.sourceCode(), row);
        List<SupplyDashboardDataTrustSourceView> result = new ArrayList<>();
        TRUST_SOURCES.forEach((code, name) -> result.add(trustSource(
                rowByCode.getOrDefault(code, new TrustSource(code, name, null, null, "EMPTY",
                        null, null, null, null, 0L, 0L, 0L, null)),
                now)));
        result.sort(Comparator.comparing(SupplyDashboardDataTrustSourceView::sourceCode));
        return result;
    }

    private static SupplyDashboardDataTrustSourceView trustSource(TrustSource row, Instant now) {
        long delayMinutes = delayMinutes(row.lastSuccessTime(), row.checkpointWatermarkTime(), now);
        String level = delayLevel(row.checkpointStatus(), row.lastSuccessTime(), delayMinutes);
        return new SupplyDashboardDataTrustSourceView(
                row.sourceCode(),
                row.sourceName(),
                row.checkpointWatermarkTime(),
                row.lastSuccessTime(),
                row.checkpointStatus() == null ? "EMPTY" : row.checkpointStatus(),
                row.lastRunId(),
                row.lastRunStatus(),
                row.lastRunStartedTime(),
                row.lastRunCompletedTime(),
                number(row.pulledCount()),
                number(row.upsertedCount()),
                number(row.skippedCount()),
                delayMinutes,
                level,
                row.failureReason(),
                trustDescription(row, level, delayMinutes));
    }

    private static long delayMinutes(Instant successTime, Instant watermarkTime, Instant now) {
        Instant base = successTime == null ? watermarkTime : successTime;
        if (base == null) return 0L;
        return Math.max(0L, Duration.between(base, now).toMinutes());
    }

    private static String delayLevel(String status, Instant successTime, long delayMinutes) {
        if (successTime == null) return "EMPTY";
        if ("FAILED".equals(status)) return "CRITICAL";
        if (delayMinutes > TRUST_CRITICAL_DELAY.toMinutes()) return "CRITICAL";
        if (delayMinutes > TRUST_WARN_DELAY.toMinutes()) return "WARN";
        return "OK";
    }

    private static String trustDescription(TrustSource row, String level, long delayMinutes) {
        if ("EMPTY".equals(level)) return row.sourceName() + "暂无成功刷新记录";
        if ("CRITICAL".equals(level)) return row.sourceName() + "数据延迟或刷新失败，需优先排查";
        if ("WARN".equals(level)) return row.sourceName() + "距上次成功刷新已超过2小时";
        return row.sourceName() + "最近刷新正常，延迟约" + delayMinutes + "分钟";
    }

    private static String overallTrustStatus(List<SupplyDashboardDataTrustSourceView> sources) {
        if (sources.stream().allMatch(item -> "EMPTY".equals(item.delayLevel()))) return "EMPTY";
        if (sources.stream().anyMatch(item -> "CRITICAL".equals(item.delayLevel()))) return "CRITICAL";
        if (sources.stream().anyMatch(item -> "WARN".equals(item.delayLevel()))) return "WARN";
        return "OK";
    }

    private static String overallTrustDescription(List<SupplyDashboardDataTrustSourceView> sources) {
        String status = overallTrustStatus(sources);
        return switch (status) {
            case "EMPTY" -> "BI 尚无成功刷新记录";
            case "CRITICAL" -> "存在刷新失败或超过6小时的数据延迟";
            case "WARN" -> "存在超过2小时的数据延迟";
            default -> "核心来源刷新正常";
        };
    }

    private static List<SupplyDashboardFilterOptionView> optionViews(List<FilterOption> items) {
        return items.stream()
                .filter(item -> item.optionValue() != null && !item.optionValue().isBlank())
                .map(item -> new SupplyDashboardFilterOptionView(
                        item.optionType(),
                        item.optionValue(),
                        item.optionLabel() == null || item.optionLabel().isBlank()
                                ? item.optionValue() : item.optionLabel(),
                        number(item.usageCount()),
                        item.parentOptionValue(),
                        item.categoryLevel(),
                        item.ordinal()))
                .toList();
    }

    private static List<SupplyDashboardFilterOptionView> defaultSourceSystems(
            List<SupplyDashboardFilterOptionView> sourceSystems) {
        Map<String, SupplyDashboardFilterOptionView> values = new LinkedHashMap<>();
        values.put("DINGHUOBAO", new SupplyDashboardFilterOptionView("SOURCE_SYSTEM", "DINGHUOBAO", "订货宝", 0L));
        values.put("FEISHU", new SupplyDashboardFilterOptionView("SOURCE_SYSTEM", "FEISHU", "飞书", 0L));
        values.put("MANUAL", new SupplyDashboardFilterOptionView("SOURCE_SYSTEM", "MANUAL", "手工订单", 0L));
        for (SupplyDashboardFilterOptionView item : sourceSystems) values.put(item.optionValue(), item);
        return List.copyOf(values.values());
    }

    private static Instant monthStart(Instant instant) {
        return BiBusinessTime.monthStart(instant);
    }

    private static long number(Long value) {
        return value == null ? 0L : value;
    }

}
