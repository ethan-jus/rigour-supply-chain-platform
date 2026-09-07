package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportRecord;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportResultView;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostImportResult;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostImportRow;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 城市端成本 BI 导入用例。 */
@Service
public final class SupplyDashboardCityCostImportService {
    private static final String WRITE_PERMISSION = "analytics:city-cost:write";
    private static final String DEFAULT_SOURCE_SYSTEM = "MANUAL_IMPORT";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final int MAX_RECORDS = 1000;

    private final SupplyDashboardStore store;
    private final Clock clock;

    public SupplyDashboardCityCostImportService(SupplyDashboardStore store, Clock analyticsClock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(analyticsClock, "analyticsClock");
    }

    public SupplyDashboardCityCostImportResultView importRecords(SupplyDashboardCityCostImportCommand command) {
        CallerIdentity actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        if (command == null || command.records() == null || command.records().isEmpty()) {
            throw badRequest("导入记录不能为空");
        }
        if (command.records().size() > MAX_RECORDS) {
            throw badRequest("单次导入不能超过" + MAX_RECORDS + "条");
        }
        String sourceSystemCode = code(defaultText(command.sourceSystemCode(), DEFAULT_SOURCE_SYSTEM), "sourceSystemCode");
        List<CityCostImportRow> rows = command.records().stream()
                .map(item -> row(sourceSystemCode, item))
                .toList();
        Instant importedAt = Instant.now(clock);
        CityCostImportResult result = store.importCityCostRecords(
                actor.tenantId().toString(), rows, importedAt);
        return new SupplyDashboardCityCostImportResultView(
                result.receivedCount(), result.upsertedCount(), result.importedAt());
    }

    private static CityCostImportRow row(String sourceSystemCode, SupplyDashboardCityCostImportRecord item) {
        if (item == null) throw badRequest("导入记录不能为空");
        String sourceRecordId = text(item.sourceRecordId(), 120, "sourceRecordId");
        if (sourceRecordId == null) throw badRequest("sourceRecordId不能为空");
        if (item.costDate() == null) throw badRequest("costDate不能为空");
        BigDecimal costAmount = amount(item.costAmount(), "costAmount");
        BigDecimal budgetAmount = item.budgetAmount() == null ? BigDecimal.ZERO : amount(item.budgetAmount(), "budgetAmount");
        return new CityCostImportRow(
                sourceSystemCode,
                sourceRecordId,
                code(item.regionCode(), "regionCode"),
                text(item.regionName(), 120, "regionName"),
                code(item.costTypeCode(), "costTypeCode"),
                text(item.costTypeName(), 120, "costTypeName"),
                item.costDate(),
                costAmount,
                budgetAmount,
                text(item.remark(), 1000, "remark"));
    }

    private static BigDecimal amount(BigDecimal value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        if (value.compareTo(BigDecimal.ZERO) < 0) throw badRequest(name + "不能小于0");
        return value;
    }

    private static String code(String value, String name) {
        String normalized = text(value, 64, name);
        if (normalized == null) throw badRequest(name + "不能为空");
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw badRequest(name + "格式无效");
        return normalized;
    }

    private static String defaultText(String value, String fallback) {
        String normalized = value == null ? null : value.strip();
        return normalized == null || normalized.isEmpty() ? fallback : normalized;
    }

    private static String text(String value, int max, String name) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
