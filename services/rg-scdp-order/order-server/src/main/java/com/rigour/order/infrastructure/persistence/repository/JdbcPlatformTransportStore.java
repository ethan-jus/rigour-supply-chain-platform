package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 来源事实与人工归属分别保存；只创建待确认记录，绝不由同步确认成本。 */
@Repository
public class JdbcPlatformTransportStore implements PlatformTransportStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LogisticsImportStore imports;

    public JdbcPlatformTransportStore(
            JdbcTemplate jdbc, ObjectMapper json, LogisticsImportStore imports) {
        this.jdbc = jdbc;
        this.json = json;
        this.imports = imports;
    }

    public void lockTenant(String tenant) {
        jdbc.update(
                "INSERT IGNORE INTO order_logistics_cost_guard(tenant_id,version) VALUES(?,0)",
                tenant);
        jdbc.queryForObject(
                "SELECT version FROM order_logistics_cost_guard WHERE tenant_id=? FOR UPDATE",
                Long.class,
                tenant);
    }

    public Row materialize(String tenant, String actor, Row row) {
        if (row.costId() != null) {
            var order = row.latestOrder() == null ? row.order() : row.latestOrder();
            BigDecimal amount = order.facts() == null ? null : order.facts().billAmount();
            // 同步只能刷新待确认候选金额；已确认成本、软删除和人工关联始终不变。
            int changed =
                    jdbc.update(
                            "UPDATE order_logistics_cost SET"
                                    + " amount=?,data_json=JSON_SET(data_json,'$.amount',CAST(? AS"
                                    + " JSON)),revision=revision+1 WHERE tenant_id=? AND id=? AND"
                                    + " deleted=0 AND status_code='DRAFT' AND NOT(amount <=> ?)",
                            amount,
                            amount == null ? "null" : amount.toPlainString(),
                            tenant,
                            row.costId(),
                            amount);
            if (changed > 0)
                jdbc.update(
                        "UPDATE order_logistics_cost_guard SET version=version+1 WHERE tenant_id=?",
                        tenant);
            return row;
        }
        var manual =
                jdbc.queryForList(
                        "SELECT id FROM order_logistics_cost WHERE tenant_id=? AND"
                            + " carrier_code='HLL' AND"
                            + " JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.ledger.platformOrderNo'))=?"
                            + " AND source_system<>'HUOLALA' ORDER BY created_time LIMIT 2",
                        tenant,
                        row.order().orderNo());
        if (manual.size() > 1) throw bad("该货拉拉单号已重复登记，请先处理已有运输记录");
        if (manual.size() == 1) {
            String existing = manual.getFirst().get("id").toString();
            jdbc.update(
                    "UPDATE order_logistics_import_order SET cost_id=?,state='GENERATED' WHERE"
                            + " tenant_id=? AND id=? AND cost_id IS NULL",
                    existing,
                    tenant,
                    row.id());
            jdbc.update(
                    "UPDATE order_logistics_cost SET source_system='HUOLALA' WHERE tenant_id=? AND"
                            + " id=?",
                    tenant,
                    existing);
            return imports.lockManaged(tenant, actor, true, row.id())
                    .withSyncOutcome(row.syncOutcome());
        }
        String id = row.id();
        var order = row.latestOrder() == null ? row.order() : row.latestOrder();
        var facts = order.facts();
        BigDecimal amount = facts == null ? null : facts.billAmount();
        String date = order.useTime().substring(0, 10);
        var transport =
                new LogisticsCostTransportDetails(
                        null,
                        List.of(),
                        List.of(),
                        "货拉拉",
                        order.raw() == null ? null : order.raw().get("运单号"),
                        null,
                        "COMPANY",
                        "MANUAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null);
        var command =
                new LogisticsCostCommand(
                        id,
                        1,
                        "货拉拉运输",
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.parse(date),
                        amount,
                        "UNKNOWN",
                        transport,
                        null,
                        null,
                        null);
        jdbc.update(
                "INSERT INTO"
                    + " order_logistics_cost(id,tenant_id,document_no,request_id,category_code,title,cost_date,amount,payment_status_code,status_code,data_json,sources_json,created_by,updated_by,source_system,carrier_code,tracking_no,origin_biz_key)"
                    + " VALUES(?,?,?,?,'LOGISTICS','货拉拉运输',?,?,'UNKNOWN','DRAFT',?,'[]',?,?,'HUOLALA','HLL',?,?)",
                id,
                tenant,
                "LC" + date.replace("-", "") + id.replace("-", "").substring(0, 12),
                id,
                date,
                amount,
                json.writeValueAsString(command),
                actor,
                actor,
                order.raw() == null ? null : order.raw().get("运单号"),
                "HUOLALA:" + order.enterpriseId() + ":" + order.orderNo());
        jdbc.update(
                "UPDATE order_logistics_import_order SET cost_id=?,state='GENERATED' WHERE"
                        + " tenant_id=? AND id=? AND cost_id IS NULL",
                id,
                tenant,
                row.id());
        jdbc.update(
                "UPDATE order_logistics_cost_guard SET version=version+1 WHERE tenant_id=?",
                tenant);
        return imports.lockManaged(tenant, actor, true, row.id())
                .withSyncOutcome(row.syncOutcome());
    }

    public Map<String, PlatformRecord> byCosts(String tenant, List<String> ids) {
        return records(tenant, ids, false);
    }

    @Override
    public PlatformRecord detail(String tenant, String id) {
        return records(tenant, List.of(id), true).get(id);
    }

    private Map<String, PlatformRecord> records(
            String tenant, List<String> ids, boolean includeHistory) {
        if (ids.isEmpty()) return Map.of();
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(ids);
        var found =
                jdbc.queryForList(
                        "SELECT cost_id,business_json,"
                                + (includeHistory
                                        ? "bill_history_json"
                                        : "NULL AS bill_history_json")
                                + " FROM order_logistics_import_order WHERE tenant_id=? AND"
                                + " source_channel='API' AND cost_id IN ("
                                + String.join(",", Collections.nCopies(ids.size(), "?"))
                                + ")",
                        args.toArray());
        var sourceMap = new HashMap<String, Row>();
        for (var source : imports.byCosts(tenant, ids)) sourceMap.put(source.costId(), source);
        var result = new LinkedHashMap<String, PlatformRecord>();
        for (var record : found) {
            String id = record.get("cost_id").toString();
            var source = sourceMap.get(id);
            if (source == null) continue;
            Draft draft =
                    record.get("business_json") == null
                            ? null
                            : json.readValue(record.get("business_json").toString(), Draft.class);
            List<CarrierOrder> history =
                    record.get("bill_history_json") == null
                            ? List.of()
                            : Arrays.asList(
                                    json.readValue(
                                            record.get("bill_history_json").toString(),
                                            CarrierOrder[].class));
            result.put(id, new PlatformRecord(source, draft, history));
        }
        return result;
    }

    public void saveDraft(
            String tenant,
            String costId,
            Draft draft,
            List<LogisticsCostSourceView> sources,
            String actor) {
        lockTenant(tenant);
        var state =
                jdbc.queryForMap(
                        "SELECT revision,status_code,deleted FROM order_logistics_cost WHERE"
                                + " tenant_id=? AND id=? FOR UPDATE",
                        tenant,
                        costId);
        if (!"DRAFT".equals(state.get("status_code"))
                || ((Number) state.get("deleted")).intValue() != 0
                || !Objects.equals(((Number) state.get("revision")).intValue(), draft.revision()))
            throw bad("记录已变化或不是待确认，请刷新");
        var previous = byCosts(tenant, List.of(costId)).get(costId);
        if (previous == null) throw bad("平台记录不存在");
        var old = previous.draft();
        if (old == null) {
            var prior =
                    jdbc.queryForObject(
                            "SELECT sources_json FROM order_logistics_cost WHERE tenant_id=? AND"
                                    + " id=?",
                            String.class,
                            tenant,
                            costId);
            if (prior != null
                    && json.readTree(prior).size() > 0
                    && (draft.reason() == null || draft.reason().isBlank()))
                throw bad("更改已有订单关联请填写原因");
        }
        if (old != null
                && old.orderCodes() != null
                && !old.orderCodes().isEmpty()
                && (!Objects.equals(old.orderCodes(), draft.orderCodes())
                        || !Objects.equals(old.transportType(), draft.transportType()))
                && (draft.reason() == null || draft.reason().isBlank()))
            throw bad("更改或解除已有订单关联请填写原因");
        // 同一关联保留首次建立时间；负责人、备注更新不能重写关联历史。
        var priorSources =
                jdbc.queryForObject(
                        "SELECT sources_json FROM order_logistics_cost WHERE tenant_id=? AND id=?",
                        String.class,
                        tenant,
                        costId);
        var snapshots =
                priorSources == null
                        ? List.<LogisticsCostSourceView>of()
                        : Arrays.asList(
                                json.readValue(priorSources, LogisticsCostSourceView[].class));
        var linkedSources =
                sources.stream()
                        .map(
                                s -> {
                                    for (var prior : snapshots)
                                        if (Objects.equals(prior.documentNo(), s.documentNo())
                                                && Objects.equals(
                                                        prior.sourceType(), s.sourceType()))
                                            return s.withBusinessSnapshot(
                                                    s.documentAmount(), prior.linkedAt());
                                    return s.withBusinessSnapshot(
                                            s.documentAmount(), java.time.Instant.now());
                                })
                        .toList();
        jdbc.update(
                "UPDATE order_logistics_import_order SET business_json=? WHERE tenant_id=? AND"
                        + " cost_id=?",
                json.writeValueAsString(draft),
                tenant,
                costId);
        jdbc.update(
                "DELETE FROM order_logistics_transport_link WHERE tenant_id=? AND source_id=?",
                tenant,
                previous.source().id());
        for (String code : draft.orderCodes())
            jdbc.update(
                    "INSERT INTO"
                        + " order_logistics_transport_link(tenant_id,source_id,order_type,order_code,updated_by)"
                        + " VALUES(?,?,?,?,?)",
                    tenant,
                    previous.source().id(),
                    draft.transportType(),
                    code,
                    actor);
        jdbc.update(
                "UPDATE order_logistics_cost SET"
                    + " sources_json=?,revision=revision+1,title=COALESCE(?,title),type_code=?,transport_purpose_code=?,owner_employee_code=?,updated_by=?,updated_time=CURRENT_TIMESTAMP(6)"
                    + " WHERE tenant_id=? AND id=?",
                json.writeValueAsString(linkedSources),
                draft.transportType() == null ? null : "运输记录",
                draft.transportType(),
                draft.transportType(),
                draft.ownerEmployeeCode(),
                actor,
                tenant,
                costId);
        jdbc.update(
                "INSERT INTO"
                    + " order_logistics_cost_audit(tenant_id,entry_id,action_code,actor_id,reason,snapshot_json)"
                    + " VALUES(?,?,'UPDATE',?,?,?)",
                tenant,
                costId,
                actor,
                draft.reason(),
                json.writeValueAsString(draft));
    }

    public void assertReadOnly(String tenant, String id, LogisticsCostCommand c) {
        if (c.ledger() != null
                && "HLL".equals(c.ledger().carrierCode())
                && c.ledger().platformOrderNo() != null
                && !c.ledger().platformOrderNo().isBlank()) {
            String no = c.ledger().platformOrderNo();
            Long duplicate =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM order_logistics_cost WHERE tenant_id=? AND (? IS"
                                + " NULL OR id<>?) AND carrier_code='HLL' AND"
                                + " JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.ledger.platformOrderNo'))=?",
                            Long.class,
                            tenant,
                            id,
                            id,
                            no);
            Long imported =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM order_logistics_import_order WHERE tenant_id=?"
                                + " AND carrier_order_no=? AND cost_id IS NOT NULL AND (? IS NULL"
                                + " OR cost_id<>?)",
                            Long.class,
                            tenant,
                            no,
                            id,
                            id);
            if ((duplicate != null && duplicate > 0) || (imported != null && imported > 0))
                throw bad("该货拉拉订单已有运输记录，请查看已有记录，避免重复登记");
        }
        if (id == null) return;
        var r = imports.byCost(tenant, id).orElse(null);
        if (r == null || !"API".equals(r.sourceChannel())) return;
        var source = r.latestOrder() == null ? r.order() : r.latestOrder();
        BigDecimal bill = source.facts() == null ? null : source.facts().billAmount();
        var p = c.ledger();
        if (p == null
                || !"HLL".equals(p.carrierCode())
                || !Objects.equals(p.platformOrderNo(), source.orderNo())
                || !Objects.equals(c.costDate(), LocalDate.parse(source.useTime().substring(0, 10)))
                || bill == null
                || p.feeItems() == null
                || p.feeItems().isEmpty()
                || p.feeItems().stream().anyMatch(f -> f.feeAmount() == null)
                || bill.compareTo(
                                p.feeItems().stream()
                                        .map(f -> f.feeAmount())
                                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                        != 0) throw bad("平台物流公司、单号、日期和账单金额只读，请刷新后核对");
        jdbc.update(
                "UPDATE order_logistics_import_order SET"
                    + " source_json=latest_source_json,source_hash=latest_source_hash,source_difference=0"
                    + " WHERE tenant_id=? AND id=? AND latest_source_json IS NOT NULL",
                tenant,
                r.id());
    }

    public void voucher(String tenant, VoucherUpdate u) {
        lockTenant(tenant);
        if (u == null
                || u.voucher() == null
                || u.voucher().fileKey() == null
                || !u.voucher().fileKey().startsWith(tenant + "/integration-huolala-vouchers/")
                || u.voucher().fileKey().contains("..")) throw bad("平台凭证标识无效");
        var found =
                jdbc.queryForList(
                        "SELECT id,source_json,latest_source_json FROM order_logistics_import_order"
                            + " WHERE tenant_id=? AND enterprise_id=? AND carrier_order_no=? FOR"
                            + " UPDATE",
                        tenant,
                        u.enterpriseId(),
                        u.orderNo());
        if (found.size() != 1) throw bad("凭证关联订单不存在");
        var row = found.getFirst();
        boolean anyMatched = false;
        for (String column : List.of("source_json", "latest_source_json")) {
            if (row.get(column) == null) continue;
            var tree = json.readTree(row.get(column).toString());
            if (!u.connectorId().equals(tree.path("facts").path("connectorId").asText()))
                throw bad("凭证来源不一致");
            boolean matched = false;
            for (var fee : tree.path("facts").path("fees"))
                if (u.lineId().equals(fee.path("lineId").asText())) {
                    for (var v : fee.path("vouchers"))
                        if (u.voucher().id().equals(v.path("id").asText())) {
                            ((tools.jackson.databind.node.ObjectNode) v)
                                    .setAll(
                                            (tools.jackson.databind.node.ObjectNode)
                                                    json.valueToTree(u.voucher()));
                            matched = true;
                        }
                }
            if (Set.of("receipts", "billVouchers").contains(u.lineId()))
                for (var v : tree.path("facts").path(u.lineId()))
                    if (u.voucher().id().equals(v.path("id").asText())) {
                        ((tools.jackson.databind.node.ObjectNode) v)
                                .setAll(
                                        (tools.jackson.databind.node.ObjectNode)
                                                json.valueToTree(u.voucher()));
                        matched = true;
                    }
            anyMatched |= matched;
            if (matched)
                jdbc.update(
                        "UPDATE order_logistics_import_order SET "
                                + column
                                + "=? WHERE tenant_id=? AND id=?",
                        json.writeValueAsString(tree),
                        tenant,
                        row.get("id"));
        }
        if (!anyMatched) throw bad("平台凭证明细尚未保存，请稍后重试");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
