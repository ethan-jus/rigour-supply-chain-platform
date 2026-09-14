package com.rigour.integration.application.service.feishu;

import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureCommand;
import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureSummary;
import com.rigour.integration.api.v1.model.FeishuReconciliationModels.SourceView;
import com.rigour.integration.api.v1.model.FeishuReconciliationModels.TableView;
import com.rigour.integration.application.port.out.FeishuBitableClient;
import com.rigour.integration.application.port.out.FeishuBitableClient.CapturedRecord;
import com.rigour.integration.application.port.out.FeishuCaptureBudget;
import com.rigour.integration.application.port.out.FeishuOnlineCaptureStore;
import com.rigour.integration.application.port.out.FeishuReconciliationSources;
import com.rigour.integration.application.port.out.FeishuReconciliationSources.Source;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** 只读在线采集编排；唯一写入为完整证据，不调用导入、附件或业务投影。 */
public final class FeishuReconciliationService {
    private final FeishuReconciliationSources sources;
    private final FeishuBitableClient client;
    private final FeishuOnlineCaptureStore store;
    private final ObjectMapper json;
    private final Clock clock;

    public FeishuReconciliationService(FeishuReconciliationSources sources, FeishuBitableClient client,
                                       FeishuOnlineCaptureStore store, ObjectMapper json, Clock clock) {
        this.sources = sources;
        this.client = client;
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    public List<SourceView> sources(CallerIdentity caller) {
        authorize(caller);
        return tenantSources(caller).stream().map(source -> new SourceView(source.id(), source.name(),
                source.filtered(), source.tables().stream()
                    .map(table -> new TableView(table.tableCode(), table.name(), table.filtered())).toList()))
                .toList();
    }

    public CaptureSummary capture(CallerIdentity caller, CaptureCommand command) {
        authorize(caller);
        if (command == null || command.sourceId() == null || command.sourceId().isBlank()
                || command.sourceId().length() > 128) {
            throw new IllegalArgumentException("请选择已配置的飞书对账来源");
        }
        Source source = tenantSources(caller).stream()
                .filter(candidate -> candidate.id().equals(command.sourceId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("飞书对账来源不存在或不可访问"));
        Set<String> sourceTableCodes = new HashSet<>();
        for (var table : source.tables()) {
            if (!Set.of("FEISHU_SALES_ORDER", "FEISHU_SALES_ORDER_LINE", "FEISHU_PRODUCT").contains(table.tableCode())
                    || !sourceTableCodes.add(table.tableCode())) {
                throw new IllegalStateException("飞书对账来源包含无效或重复的业务表");
            }
        }
        if (!sourceTableCodes.containsAll(Set.of("FEISHU_SALES_ORDER", "FEISHU_SALES_ORDER_LINE"))) {
            throw new IllegalStateException("飞书对账来源必须同时配置销售订单和明细表");
        }
        FeishuCaptureBudget budget = new FeishuCaptureBudget();
        Instant startedAt = clock.instant();
        List<Map<String, Object>> tables = new ArrayList<>();
        int records = 0;
        int pages = 0;
        for (var table : source.tables().stream().sorted(Comparator
                .comparing(FeishuReconciliationSources.Table::tableCode)
                .thenComparing(FeishuReconciliationSources.Table::tableId)).toList()) {
            budget.checkTime();
            var captured = client.captureRecords(source.appToken(), table.tableId(), table.viewId(), budget);
            if (captured == null || !captured.complete() || captured.pageCount() < 1) {
                throw FeishuCaptureBudget.failure("INCOMPLETE", "飞书分页采集未完成，未保存对账证据");
            }
            records += captured.rows().size();
            pages += captured.pageCount();
            if (records > FeishuCaptureBudget.MAX_RECORDS || pages > FeishuCaptureBudget.MAX_PAGES) {
                throw FeishuCaptureBudget.failure("LIMIT", "飞书采集结果超过上限");
            }
            Set<String> identities = new HashSet<>();
            for (CapturedRecord row : captured.rows()) {
                if (row == null || row.recordId() == null || row.recordId().isBlank()
                        || !identities.add(row.recordId())) {
                    throw FeishuCaptureBudget.failure("RECORD_ID", "飞书记录标识缺失或重复");
                }
            }
            Map<String, Object> payloadTable = new LinkedHashMap<>();
            payloadTable.put("tableId", table.tableId());
            payloadTable.put("tableCode", table.tableCode());
            payloadTable.put("viewId", table.viewId());
            payloadTable.put("rows", captured.rows().stream().sorted(Comparator.comparing(CapturedRecord::recordId))
                    .map(FeishuReconciliationService::payloadRow).toList());
            tables.add(payloadTable);
        }
        if (tables.isEmpty()) throw new IllegalStateException("飞书对账来源未配置业务表");
        budget.checkTime();
        String payload = json.writeValueAsString(canonical(Map.of("tables", tables)));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FeishuCaptureBudget.MAX_RESPONSE_BYTES) {
            throw FeishuCaptureBudget.failure("SIZE_LIMIT", "飞书采集证据超过大小上限");
        }
        String checksum = sha256(bytes);
        budget.checkTime();
        CaptureSummary summary = new CaptureSummary(UUID.randomUUID(), source.id(), source.name(),
                source.sourceUrl(), startedAt, clock.instant(), true, source.filtered(), checksum, records, pages);
        store.save(caller.tenantId(), caller.userId(), summary, payload);
        return summary;
    }

    private List<Source> tenantSources(CallerIdentity caller) {
        // 即使来源端口实现错误，也不把其他租户的来源交给调用方。
        return sources.forTenant(caller.tenantId()).stream()
                .filter(source -> caller.tenantId().equals(source.tenantId())).toList();
    }

    private static void authorize(CallerIdentity caller) {
        if (caller == null || !"TENANT".equals(caller.principalScope())
                || caller.tenantId() == null || caller.userId() == null) {
            throw new AuthorizationDeniedException("tenant-user");
        }
        Set<String> permissions = caller.permissions();
        if (!permissions.contains("*:*:*") && (!permissions.contains("analytics:reconciliation:write")
                || (!permissions.contains("integration:feishu:import")
                    && !permissions.contains("integration:feishu:write")))) {
            throw new AuthorizationDeniedException("analytics:reconciliation:write+integration:feishu:import");
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put((String) key, canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(FeishuReconciliationService::canonical).toList();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        return value;
    }

    private static Map<String, Object> payloadRow(CapturedRecord row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("recordId", row.recordId());
        values.put("fields", row.fields());
        if (row.createdTime() != null) values.put("createdTime", row.createdTime());
        if (row.lastModifiedTime() != null) values.put("lastModifiedTime", row.lastModifiedTime());
        return values;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }
}
