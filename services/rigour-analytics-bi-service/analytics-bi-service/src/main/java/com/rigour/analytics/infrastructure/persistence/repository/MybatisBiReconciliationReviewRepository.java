package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.Fact;
import com.rigour.analytics.application.port.out.BiReconciliationReviewStore;
import com.rigour.analytics.infrastructure.persistence.mapper.BiReconciliationReviewMapper;
import com.rigour.shared.core.api.ApiErrorDetail;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 类型化读取采集结果，金额不先舍入，来源字段保留空值。 */
@Repository
public class MybatisBiReconciliationReviewRepository implements BiReconciliationReviewStore {
    static final int ONLINE_ROW_LIMIT = 20000;
    static final int MAX_ONLINE_PAYLOAD_BYTES = 32 * 1024 * 1024;
    private final BiReconciliationReviewMapper mapper;
    private final ObjectMapper json;
    public MybatisBiReconciliationReviewRepository(BiReconciliationReviewMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }
    @Override public Optional<OnlineCapture> onlineCapture(String tenant, String captureId) {
        Map<String, Object> row;
        try {
            row = mapper.onlineCapture(tenant, captureId, MAX_ONLINE_PAYLOAD_BYTES);
        } catch (RuntimeException failure) {
            if (!sourceSelectDenied(failure)) throw failure;
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "在线对账暂不可用：BI服务缺少在线采集来源的只读权限。请联系管理员补齐授权后，使用本次采集重新生成复核，无需重新导入订单。",
                    List.of(new ApiErrorDetail(null, "BI_ONLINE_SOURCE_READ_NOT_PROVISIONED",
                            "请补齐在线采集来源的最小只读授权后重试；不需要授予写权限或重新导入订单。")));
        }
        if (row == null) return Optional.empty();
        boolean complete = flag(row.get("complete"));
        Instant started = time(row.get("startedAt")), completed = time(row.get("completedAt"));
        if (!complete || completed == null) return Optional.empty();
        if (started == null || started.isAfter(completed) || !captureId.equals(text(row, "captureId"))) {
            throw invalid("在线采集窗口或来源标识无效");
        }
        int count = positiveInt(row.get("recordCount"), ONLINE_ROW_LIMIT, "在线来源记录数"),
                pages = positiveInt(row.get("pageCount"), ONLINE_ROW_LIMIT, "在线采集分页数");
        String checksum = text(row, "checksum"), payload = text(row, "payloadJson");
        if (checksum == null || !checksum.matches("[a-fA-F0-9]{64}") || blank(text(row, "sourceId"))) {
            throw invalid("在线采集缺少有效来源或内容校验标识");
        }
        if (payload == null || payload.length() > MAX_ONLINE_PAYLOAD_BYTES
                || payload.getBytes(StandardCharsets.UTF_8).length > MAX_ONLINE_PAYLOAD_BYTES
                || row.get("payloadBytes") instanceof Number n && n.longValue() > MAX_ONLINE_PAYLOAD_BYTES) {
            throw invalid("在线来源内容超过32MiB或缺失，本次未创建复核");
        }
        if (!checksum.equalsIgnoreCase(sha256(payload))) throw invalid("在线来源内容与采集校验值不一致");
        JsonNode root;
        try { root = json.readTree(payload); }
        catch (RuntimeException ex) { throw invalid("在线来源内容无法解析，本次未创建复核"); }
        JsonNode tables = root == null ? null : root.get("tables");
        if (tables == null || !tables.isArray() || tables.size() < 2 || tables.size() > 3) {
            throw invalid("在线来源必须包含销售订单和订单明细，可附带商品关联表");
        }
        Set<String> codes = new HashSet<>(), tableIds = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean filtered = flag(row.get("filtered"));
        for (JsonNode table : tables) {
            String code = scalar(table.get("tableCode")), tableId = scalar(table.get("tableId"));
            if (!Set.of("FEISHU_SALES_ORDER", "FEISHU_SALES_ORDER_LINE", "FEISHU_PRODUCT").contains(code == null ? "" : code)
                    || !codes.add(code) || blank(tableId) || !tableIds.add(tableId)) {
                throw invalid("在线来源表类型或表标识缺失、重复");
            }
            JsonNode view = table.get("viewId");
            filtered |= view != null && !view.isNull() && (!view.isTextual() || !view.asText().isBlank());
            JsonNode tableComplete = table.get("complete");
            if (tableComplete != null && (!tableComplete.isBoolean() || !tableComplete.asBoolean())) {
                throw invalid("在线来源存在未完整采集的表");
            }
            JsonNode records = table.get("rows");
            if (records == null || !records.isArray() || records.isEmpty()) {
                throw invalid("在线来源表没有可核对记录，不能生成一致结论");
            }
            Set<String> recordIds = new HashSet<>();
            for (JsonNode record : records) {
                String recordId = scalar(record.get("recordId"));
                JsonNode fields = record.get("fields");
                if (blank(recordId) || !recordIds.add(recordId) || fields == null || !fields.isObject() || fields.isEmpty()) {
                    throw invalid("在线来源记录标识或业务字段缺失、重复");
                }
                if (rows.size() >= ONLINE_ROW_LIMIT) throw invalid("在线来源订单及明细超过20000行，请缩小采集范围");
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("tableCode", code);
                raw.put("recordId", recordId);
                raw.put("valuesJson", json.writeValueAsString(fields));
                raw.put("sheetName", tableId);
                raw.put("rowNumber", rows.size() + 1);
                for (String name : List.of("createdTime", "lastModifiedTime")) {
                    JsonNode timestamp = record.get(name);
                    if (timestamp != null && timestamp.isIntegralNumber() && timestamp.canConvertToLong()) raw.put(name, timestamp.longValue());
                }
                rows.add(raw);
            }
        }
        if (!codes.containsAll(Set.of("FEISHU_SALES_ORDER", "FEISHU_SALES_ORDER_LINE"))) {
            throw invalid("在线来源缺少销售订单或订单明细表");
        }
        if (rows.size() != count || pages < tables.size()) {
            throw invalid("在线来源记录或分页数量与采集证据不一致");
        }
        var evidence = new BiReconciliationReview.OnlineEvidence(captureId, text(row, "sourceId"),
                text(row, "sourceName"), text(row, "sourceUrl"), started, completed, complete, filtered, pages, count, false);
        var version = new BiReconciliationReview.Version(null, text(row, "sourceName"), checksum,
                text(row, "sourceUrl"), completed, "CAPTURED_NON_ATOMIC", count, evidence);
        return Optional.of(new OnlineCapture(version, List.copyOf(rows), complete && !filtered));
    }
    /** 仅在线来源 SELECT 调用边界翻译 MySQL 授权错误，其他 SQL 故障不得误报成权限缺口。 */
    private static boolean sourceSelectDenied(Throwable failure) {
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof SQLException sql) {
                if ("42000".equals(sql.getSQLState()) && (sql.getErrorCode() == 1142 || sql.getErrorCode() == 1143)) return true;
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (current.getCause() != null) pending.add(current.getCause());
        }
        return false;
    }
    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() == 1;
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String scalar(JsonNode node) { return node != null && node.isTextual() ? node.asText().strip() : null; }
    private static int positiveInt(Object value, int max, String label) {
        try {
            int count = new BigDecimal(String.valueOf(value)).intValueExact();
            if (count > 0 && count <= max) return count;
        } catch (NumberFormatException | ArithmeticException ignored) { }
        throw invalid(label + "必须为正数且不超过" + max);
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
    @Override public Optional<BiReconciliationReview.Version> version(String tenant, String batchId) {
        Map<String,Object> row = mapper.version(tenant,batchId);
        if (row == null) return Optional.empty();
        return Optional.of(new BiReconciliationReview.Version(text(row,"batchId"),text(row,"fileName"),
                text(row,"checksum"),text(row,"sourceUrl"),time(row.get("uploadedAt")),
                text(row,"importStatus"),((Number)row.get("rowCount")).longValue()));
    }
    @Override public List<Map<String,Object>> sourceRows(String tenant,String batchId,int limit) {
        return mapper.sourceRows(tenant,batchId,limit);
    }
    @Override public List<Fact> businessRows(String tenant,int limit) {
        return facts(mapper.businessOrders(tenant,limit),mapper.businessLines(tenant,limit),limit);
    }
    @Override public List<Fact> biRows(String tenant,int limit) {
        return facts(mapper.biOrders(tenant,limit),mapper.biLines(tenant,limit),limit);
    }
    private static List<Fact> facts(List<Map<String,Object>> orders,List<Map<String,Object>> lines,int limit) {
        // 每一粒度单独检测截断，不把被截断的集合包装成完整快照。
        if (orders.size() >= limit || lines.size() >= limit) throw new IllegalStateException("对账数据超过单次采集上限，请由管理员缩小来源数据集后复核");
        List<Fact> result = new ArrayList<>();
        for (var row : orders) result.add(fact(row));
        for (var row : lines) result.add(fact(row));
        return result;
    }
    private static Fact fact(Map<String,Object> row) {
        String no=text(row,"orderNo"), kind=text(row,"kind");
        String key="ORDER".equals(kind) ? "ORDER|"+no : "SKU|"+no+"|"+text(row,"identityKey");
        List<String> issues=new ArrayList<>();
        if (no==null) issues.add("来源订单号缺失");
        if ("UNLINKED".equals(text(row,"associationEvidence"))) issues.add("系统商品/SKU关联尚未完整，按原订单行保留，不合并未知商品");
        return new Fact(key,no,kind,text(row,"city"),text(row,"sales"),text(row,"customer"),
                text(row,"product"),text(row,"specification"),text(row,"unit"),time(row.get("orderDate")),
                decimal(row.get("amount")),decimal(row.get("paid")),decimal(row.get("unpaid")),
                decimal(row.get("quantity")),time(row.get("updatedAt")),null,false,
                List.copyOf(issues),text(row,"unit"),null,"SYSTEM",
                "SKU".equals(kind)?text(row,"associationEvidence"):"SYSTEM",null,
                text(row,"sourceProductId"),text(row,"sourceProductCode"),text(row,"systemLineId"),
                text(row,"systemVariantId"),null);
    }
    @Override public void save(String tenant,String actor,BiReconciliationReview review) {
        String identity = review.sourceVersion().onlineEvidence() == null ? review.sourceVersion().batchId()
                : review.sourceVersion().onlineEvidence().captureId();
        mapper.insert(tenant,actor,review.id(),identity,
                LocalDateTime.ofInstant(review.capturedAt(),ZoneOffset.UTC),
                LocalDateTime.ofInstant(review.completedAt(),ZoneOffset.UTC),json.writeValueAsString(review));
    }
    @Override public Optional<BiReconciliationReview> find(String tenant,String actor,String id) {
        return Optional.ofNullable(mapper.find(tenant,actor,id)).map(value->json.readValue(value,BiReconciliationReview.class));
    }
    @Override public List<BiReconciliationReview.History> history(String tenant,String actor) {
        return mapper.history(tenant,actor).stream().map(row->new BiReconciliationReview.History(text(row,"id"),
                text(row,"fileName"),time(row.get("capturedAt")),text(row,"status"))).toList();
    }
    private static String text(Map<String,Object> row,String key) { return row.get(key)==null?null:row.get(key).toString(); }
    private static BigDecimal decimal(Object value) { return value==null?null:new BigDecimal(value.toString()); }
    public static Instant time(Object value) {
        if (value==null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof java.sql.Timestamp stamp) return stamp.toInstant();
        return Instant.parse(value.toString());
    }
}
