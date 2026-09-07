package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustSourceView;
import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.api.v1.model.SupplyDashboardReconciliationItemView;
import com.rigour.analytics.api.v1.model.SupplyDashboardReconciliationView;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FeishuArchiveWrite;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FilterOption;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FilterOptions;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ReconciliationItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TrustSource;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 供应链 BI 数据可信、对账和飞书旧数据归档用例。 */
@Service
public final class SupplyDashboardGovernanceService {
    private static final String READ_PERMISSION = "analytics:dashboard:read";
    private static final String LEGACY_READ_PERMISSION = "analytics:legacy-archive:read";
    private static final String LEGACY_WRITE_PERMISSION = "analytics:legacy-archive:write";
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

    public SupplyDashboardReconciliationView reconciliation(
            Instant from, Instant to, String regionCode, String ownerStaffCode,
            String customerTypeCode, Long productCategoryId, String sourceSystemCode) {
        CallerIdentity actor = readActor();
        Instant now = Instant.now(clock);
        Instant normalizedTo = to == null ? now : to;
        Instant normalizedFrom = from == null ? monthStart(normalizedTo) : from;
        if (normalizedFrom.isAfter(normalizedTo)) throw badRequest("from不能晚于to");
        SupplyDashboardFilter filter = new SupplyDashboardFilter(
                normalizedFrom,
                normalizedTo,
                code(regionCode, "regionCode"),
                optionalText(ownerStaffCode, 50, "ownerStaffCode"),
                code(customerTypeCode, "customerTypeCode"),
                positiveId(productCategoryId, "productCategoryId"),
                sourceSystemCode(sourceSystemCode));
        SupplyDashboardStore.ReconciliationData data = store.reconciliation(actor.tenantId().toString(), filter);
        List<SupplyDashboardReconciliationItemView> items = data.items().stream()
                .map(SupplyDashboardGovernanceService::reconciliationItem).toList();
        return new SupplyDashboardReconciliationView(
                data.from() == null ? normalizedFrom : data.from(),
                data.to() == null ? normalizedTo : data.to(),
                data.observedAt() == null ? now : data.observedAt(),
                overallReconciliationStatus(items),
                items);
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

    public List<SupplyDashboardFeishuArchiveView> feishuArchives() {
        CallerIdentity actor = legacyReadActor();
        return store.feishuArchives(actor.tenantId().toString()).stream()
                .map(SupplyDashboardGovernanceService::archiveView)
                .toList();
    }

    public SupplyDashboardFeishuArchiveView registerFeishuArchive(SupplyDashboardFeishuArchiveCommand command) {
        CallerIdentity actor = legacyWriteActor();
        if (command == null) throw badRequest("飞书归档登记不能为空");
        Instant now = Instant.now(clock);
        FeishuArchiveWrite write = new FeishuArchiveWrite(
                requiredCode(command.archiveCode(), "archiveCode"),
                text(command.tableId(), 120, "tableId"),
                optionalText(command.viewId(), 120, "viewId"),
                text(command.tableName(), 120, "tableName"),
                text(command.fileName(), 255, "fileName"),
                fileFormat(command.fileFormat()),
                text(command.exportedBy(), 100, "exportedBy"),
                requiredTime(command.exportedTime(), "exportedTime"),
                requiredTime(command.frozenTime(), "frozenTime"),
                nonNegative(command.recordCount(), "recordCount"),
                checksum(command.checksumSha256()),
                optionalText(command.storageUri(), 500, "storageUri"),
                optionalText(command.fieldMappingUri(), 500, "fieldMappingUri"),
                optionalText(command.reconciliationReportUri(), 500, "reconciliationReportUri"),
                optionalText(command.remark(), 1000, "remark"));
        if (write.exportedTime().isBefore(write.frozenTime())) {
            throw badRequest("exportedTime不能早于frozenTime");
        }
        return archiveView(store.registerFeishuArchive(actor.tenantId().toString(), write, now));
    }

    private static CallerIdentity readActor() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(READ_PERMISSION);
        return caller;
    }

    private static CallerIdentity legacyReadActor() {
        CallerIdentity caller = readActor();
        AuthorizationContext.requirePermission(LEGACY_READ_PERMISSION);
        return caller;
    }

    private static CallerIdentity legacyWriteActor() {
        CallerIdentity caller = readActor();
        AuthorizationContext.requirePermission(LEGACY_WRITE_PERMISSION);
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

    private static SupplyDashboardReconciliationItemView reconciliationItem(ReconciliationItem item) {
        long sourceBusinessDiff = number(item.sourceRowCount()) == 0L
                ? 0L : number(item.businessRowCount()) - number(item.sourceRowCount());
        long businessBiDiff = number(item.biRowCount()) - number(item.businessRowCount());
        BigDecimal amountDiff = money(item.biAmount()).subtract(money(item.businessAmount()));
        String status = reconciliationStatus(item, sourceBusinessDiff, businessBiDiff, amountDiff);
        return new SupplyDashboardReconciliationItemView(
                item.subjectCode(),
                item.subjectName(),
                number(item.sourceRowCount()),
                number(item.businessRowCount()),
                number(item.biRowCount()),
                money(item.sourceAmount()),
                money(item.businessAmount()),
                money(item.biAmount()),
                sourceBusinessDiff,
                businessBiDiff,
                amountDiff,
                status,
                reconciliationDescription(item, status));
    }

    private static String reconciliationStatus(
            ReconciliationItem item, long sourceBusinessDiff, long businessBiDiff, BigDecimal amountDiff) {
        if (number(item.sourceRowCount()) == 0L && number(item.businessRowCount()) == 0L && number(item.biRowCount()) == 0L) {
            return "EMPTY";
        }
        if (sourceBusinessDiff != 0L || businessBiDiff != 0L || amountDiff.abs().compareTo(MONEY_TOLERANCE) > 0) {
            return "DIFF";
        }
        return "PASS";
    }

    private static String reconciliationDescription(ReconciliationItem item, String status) {
        return switch (status) {
            case "EMPTY" -> item.subjectName() + "在当前筛选范围内暂无可对账数据";
            case "DIFF" -> item.subjectName() + "存在来源/业务/BI 数量或金额差异，仅提示定位，不自动修正";
            default -> item.subjectName() + "来源、业务表和 BI 表口径一致";
        };
    }

    private static String overallReconciliationStatus(List<SupplyDashboardReconciliationItemView> items) {
        if (items.stream().anyMatch(item -> "DIFF".equals(item.status()))) return "DIFF";
        if (items.stream().allMatch(item -> "EMPTY".equals(item.status()))) return "EMPTY";
        return "PASS";
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
        values.put("MANUAL", new SupplyDashboardFilterOptionView("SOURCE_SYSTEM", "MANUAL", "手工订单", 0L));
        for (SupplyDashboardFilterOptionView item : sourceSystems) values.put(item.optionValue(), item);
        return List.copyOf(values.values());
    }

    private static SupplyDashboardFeishuArchiveView archiveView(SupplyDashboardStore.FeishuArchiveRow row) {
        return new SupplyDashboardFeishuArchiveView(
                row.id(),
                row.archiveCode(),
                row.tableId(),
                row.viewId(),
                row.tableName(),
                row.fileName(),
                row.fileFormat(),
                row.exportedBy(),
                row.exportedTime(),
                row.frozenTime(),
                number(row.recordCount()),
                row.checksumSha256(),
                row.storageUri(),
                row.fieldMappingUri(),
                row.reconciliationReportUri(),
                row.archiveStatusCode(),
                row.remark(),
                row.createdTime(),
                row.updatedTime());
    }

    private static Instant monthStart(Instant instant) {
        LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String code(String value, String name) {
        String normalized = upper(value, name);
        if (normalized == null) return null;
        if (!CODE.matcher(normalized).matches()) throw badRequest(name + "格式无效");
        return normalized;
    }

    private static String requiredCode(String value, String name) {
        String normalized = code(value, name);
        if (normalized == null) throw badRequest(name + "不能为空");
        return normalized;
    }

    private static String sourceSystemCode(String value) {
        String normalized = code(value, "sourceSystemCode");
        if ("DHB".equals(normalized)) return "DINGHUOBAO";
        return normalized;
    }

    private static String fileFormat(String value) {
        String normalized = requiredCode(value, "fileFormat");
        if (!List.of("CSV", "XLSX").contains(normalized)) throw badRequest("fileFormat只支持CSV或XLSX");
        return normalized;
    }

    private static String checksum(String value) {
        String normalized = text(value, 64, "checksumSha256");
        if (!CHECKSUM.matcher(normalized).matches()) throw badRequest("checksumSha256必须是64位SHA-256十六进制值");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value, String name) {
        String normalized = optionalText(value, 64, name);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String text(String value, int max, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        String normalized = value.strip();
        if (normalized.isEmpty()) throw badRequest(name + "不能为空");
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static String optionalText(String value, int max, String name) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static Long positiveId(Long value, String name) {
        if (value == null) return null;
        if (value < 1) throw badRequest(name + "必须大于0");
        return value;
    }

    private static Long nonNegative(Long value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        if (value < 0) throw badRequest(name + "不能小于0");
        return value;
    }

    private static Instant requiredTime(Instant value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        return value;
    }

    private static long number(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
