package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.ShipmentTransportModels.*;
import com.rigour.order.api.v1.model.TransportRecordModels.Draft;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

/** 只写Order自有事实。先取统一企业锁，再锁成本；与货拉拉同步/手工保存取锁顺序一致。 */
@Repository
public class JdbcShipmentTransportStore implements ShipmentTransportStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlatformTransportStore platform;
    private final OrderLogisticsCostStore costs;

    public JdbcShipmentTransportStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransportStore platform,
            OrderLogisticsCostStore costs) {
        this.jdbc = jdbc;
        this.json = json;
        this.platform = platform;
        this.costs = costs;
    }

    @Override
    public Source sync(
            String tenant,
            String actor,
            Command command,
            LogisticsCostCommand transport,
            LogisticsCostSourceView order,
            Long warehouseId) {
        platform.lockTenant(tenant);
        var records =
                jdbc.queryForList(
                        "SELECT * FROM order_transport_shipment_source WHERE tenant_id=? AND"
                            + " platform_code='DHB' AND connector_id=? AND source_shipment_id=? FOR"
                            + " UPDATE",
                        tenant,
                        command.connectorId().toString(),
                        command.shipmentId());
        String body = json.writeValueAsString(command), fp = fingerprint(body);
        Map<String, Object> old = records.isEmpty() ? null : records.getFirst();
        if (old != null) {
            var previous = json.readValue(old.get("latest_json").toString(), Command.class);
            if (previous.sourceUpdatedAt() != null
                    && command.sourceUpdatedAt() != null
                    && command.sourceUpdatedAt().isBefore(previous.sourceUpdatedAt()))
                return source(old);
            if (fp.equals(old.get("fingerprint")) && old.get("issue") == null) return source(old);
        }
        String sourceId = old == null ? UUID.randomUUID().toString() : old.get("id").toString();
        String costId = old == null ? null : Objects.toString(old.get("cost_id"), null);
        String issue = null;
        boolean difference = false;
        boolean shipped = Set.of("receivedin", "received").contains(command.sourceStatus());
        if (shipped && costId == null) {
            var matches = matches(tenant, command, transport.ledger().carrierCode());
            if (matches.size() > 1) issue = "同一快递单号对应多笔运输，请核对已有记录";
            else if (matches.size() == 1) {
                var row =
                        costs.entry(
                                tenant,
                                "LOGISTICS",
                                matches.getFirst(),
                                "order:logistics-cost:read");
                var codes = codes(row);
                if (!codes.isEmpty() && !codes.contains(order.documentNo()))
                    issue = "同一快递单号已有其他业务关联，请核对是否同一运输";
                else if (!codes.contains(order.documentNo()) && !"DRAFT".equals(row.statusCode()))
                    issue = "匹配运输已确认或作废，请先核对原记录，不能自动变更关联";
                else {
                    costId = row.id();
                    if (!codes.contains(order.documentNo()))
                        linkPlatform(tenant, actor, row, order);
                }
            } else {
                costId = UUID.randomUUID().toString();
                var data = withRequest(transport, costId, null, transport.ledger());
                var linked =
                        data.ledger()
                                .withSources(
                                        List.of(
                                                data.ledger()
                                                        .sources()
                                                        .getFirst()
                                                        .withBusinessSnapshot(
                                                                order.documentAmount(),
                                                                order.documentDate(),
                                                                Instant.now())));
                data = data.withLedger(linked, null);
                jdbc.update(
                        "INSERT INTO"
                            + " order_logistics_cost(id,tenant_id,document_no,request_id,category_code,title,type_code,transport_purpose_code,city_code,warehouse_id,source_warehouse_id,cost_date,amount,payment_status_code,status_code,data_json,sources_json,created_by,updated_by,source_system,carrier_code,delivery_mode_code,cost_month,origin_biz_key)"
                            + " VALUES(?,?,?,?,'LOGISTICS',?,'DELIVERY','DELIVERY',?,?,?,?,NULL,'UNKNOWN','DRAFT',?,?,?,?,'DHB',?,?,?,?)",
                        costId,
                        tenant,
                        "LC"
                                + data.costDate().toString().replace("-", "")
                                + costId.replace("-", "").substring(0, 12),
                        costId,
                        data.title(),
                        data.cityCode(),
                        warehouseId,
                        warehouseId,
                        data.costDate(),
                        json.writeValueAsString(data),
                        json.writeValueAsString(linked.sources()),
                        actor,
                        actor,
                        linked.carrierCode(),
                        linked.deliveryModeCode(),
                        linked.costMonth(),
                        "DHB:" + command.connectorId() + ":" + command.shipmentId());
                changed(tenant, costId, actor, "SOURCE_SYNC", "订货宝发货物流自动带入", data);
            }
        }
        if (old != null
                && !Objects.equals(
                        ((Number) old.get("sales_order_id")).longValue(), command.salesOrderId())) {
            issue = "来源发货更换了销售订单，请核对原运输关联";
            shipped = false;
        }
        if (costId != null && old != null) {
            var states =
                    jdbc.queryForList(
                            "SELECT status_code,revision,deleted,source_system,data_json FROM"
                                    + " order_logistics_cost WHERE tenant_id=? AND id=? FOR UPDATE",
                            tenant,
                            costId);
            if (!states.isEmpty()) {
                var state = states.getFirst();
                difference = !shipped || "CONFIRMED".equals(state.get("status_code"));
                if (!shipped && issue == null)
                    issue =
                            "cancelled".equals(command.sourceStatus())
                                    ? "来源发货已取消，请核对运输记录"
                                    : "来源仍为待发货，请核对实际发货情况";
                else if ("DRAFT".equals(state.get("status_code"))
                        && ((Number) state.get("deleted")).intValue() == 0
                        && "DHB".equals(state.get("source_system"))) {
                    var prior =
                            json.readValue(
                                    state.get("data_json").toString(), LogisticsCostCommand.class);
                    // 新来源只更新收发货事实；人工补充的账单、付款、备注、凭证和费用分摊保留。
                    var nextLedger = transport.ledger();
                    var oldLedger = prior.ledger();
                    boolean factsChanged =
                            !Objects.equals(prior.costDate(), transport.costDate())
                                    || !Objects.equals(
                                            oldLedger.carrierCode(), nextLedger.carrierCode())
                                    || !Objects.equals(
                                            oldLedger.deliveryModeCode(),
                                            nextLedger.deliveryModeCode())
                                    || !Objects.equals(
                                            oldLedger.platformOrderNo(),
                                            nextLedger.platformOrderNo())
                                    || !Objects.equals(oldLedger.sender(), nextLedger.sender())
                                    || !Objects.equals(
                                            oldLedger.receivingPoints(),
                                            nextLedger.receivingPoints());
                    nextLedger =
                            new LogisticsCostLedger(
                                    2,
                                    "DELIVERY",
                                    nextLedger.deliveryModeCode(),
                                    nextLedger.carrierCode(),
                                    oldLedger.costMonth(),
                                    null,
                                    oldLedger.transportMethodCode(),
                                    nextLedger.sender(),
                                    nextLedger.receivingPoints(),
                                    oldLedger.feeItems(),
                                    oldLedger.sources(),
                                    oldLedger.nonSalesPurposeCode(),
                                    oldLedger.noTrackingReason(),
                                    oldLedger.noSourceReason(),
                                    nextLedger.scopeWarehouseIds(),
                                    nextLedger.scopeCityCodes(),
                                    oldLedger.sourceSync(),
                                    oldLedger.driverName(),
                                    oldLedger.vehicleNo(),
                                    nextLedger.platformOrderNo(),
                                    oldLedger.manualBillItems());
                    var data =
                            new LogisticsCostCommand(
                                    prior.requestId(),
                                    ((Number) state.get("revision")).intValue(),
                                    prior.title(),
                                    "DELIVERY",
                                    transport.cityCode(),
                                    transport.cityName(),
                                    prior.departmentId(),
                                    prior.ownerEmployeeCode(),
                                    transport.costDate(),
                                    prior.amount(),
                                    prior.paymentStatusCode(),
                                    null,
                                    prior.remark(),
                                    prior.reason(),
                                    nextLedger);
                    if (factsChanged) {
                        jdbc.update(
                                "UPDATE order_logistics_cost SET"
                                    + " data_json=?,carrier_code=?,delivery_mode_code=?,cost_date=?,city_code=?,revision=revision+1,updated_by=?,updated_time=CURRENT_TIMESTAMP(6)"
                                    + " WHERE tenant_id=? AND id=?",
                                json.writeValueAsString(data),
                                nextLedger.carrierCode(),
                                nextLedger.deliveryModeCode(),
                                data.costDate(),
                                data.cityCode(),
                                actor,
                                tenant,
                                costId);
                        changed(tenant, costId, actor, "SOURCE_SYNC", "订货宝发货物流更新", data);
                    }
                }
            }
        }
        if (!shipped && costId == null)
            issue = "cancelled".equals(command.sourceStatus()) ? "来源发货已取消" : "待发货，尚未生成运输记录";
        if (shipped && transport.ledger().deliveryModeCode() == null)
            issue = first(issue, "来源配送方式尚未映射，请维护数据字典的来源映射后重新同步");
        if (shipped
                && transport.ledger().carrierCode() == null
                && !"SELF".equals(transport.ledger().deliveryModeCode()))
            issue = first(issue, "来源物流公司尚未映射到数据字典，请维护映射后重新同步");
        if (old == null)
            jdbc.update(
                    "INSERT INTO"
                        + " order_transport_shipment_source(id,tenant_id,platform_code,connector_id,source_shipment_id,source_shipment_no,sales_order_id,cost_id,latest_json,fingerprint,issue,difference_pending,created_by,updated_by)"
                        + " VALUES(?,?,'DHB',?,?,?,?,?,?,?,?,?,?,?)",
                    sourceId,
                    tenant,
                    command.connectorId().toString(),
                    command.shipmentId(),
                    command.shipmentNo(),
                    command.salesOrderId(),
                    costId,
                    body,
                    fp,
                    issue,
                    difference ? 1 : 0,
                    actor,
                    actor);
        else
            jdbc.update(
                    "UPDATE order_transport_shipment_source SET"
                        + " source_shipment_no=?,sales_order_id=?,cost_id=?,latest_json=?,fingerprint=?,issue=?,difference_pending=?,updated_by=?,updated_time=CURRENT_TIMESTAMP(6)"
                        + " WHERE tenant_id=? AND id=?",
                    command.shipmentNo(),
                    costId != null ? old.get("sales_order_id") : command.salesOrderId(),
                    costId,
                    body,
                    fp,
                    issue,
                    difference ? 1 : 0,
                    actor,
                    tenant,
                    sourceId);
        jdbc.update(
                "INSERT INTO"
                    + " order_transport_shipment_history(id,tenant_id,platform_code,source_id,fingerprint,source_json,created_by)"
                    + " VALUES(?,?,'DHB',?,?,?,?) ON DUPLICATE KEY UPDATE fingerprint=fingerprint",
                UUID.randomUUID().toString(),
                tenant,
                sourceId,
                fp,
                body,
                actor);
        return new Source(sourceId, costId, command, issue, difference);
    }

    private List<String> matches(String tenant, Command c, String carrier) {
        if (carrier == null || c.trackingNumbers().size() != 1) return List.of();
        String no = c.trackingNumbers().getFirst();
        return jdbc.queryForList(
                "SELECT DISTINCT c.id FROM order_logistics_cost c LEFT JOIN"
                    + " order_logistics_import_order i ON i.tenant_id=c.tenant_id AND"
                    + " i.cost_id=c.id WHERE c.tenant_id=? AND c.carrier_code=? AND c.deleted=0 AND"
                    + " c.status_code<>'VOID' AND"
                    + " (JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.ledger.platformOrderNo'))=? OR"
                    + " i.carrier_order_no=?)",
                String.class,
                tenant,
                carrier,
                no,
                no);
    }

    private void linkPlatform(
            String tenant,
            String actor,
            LogisticsCostDetailView row,
            LogisticsCostSourceView order) {
        var p = platform.detail(tenant, row.id());
        if (p == null) throw bad("匹配运输未关联当前销售单，请核对后手工关联");
        var o = p.source().latestOrder() == null ? p.source().order() : p.source().latestOrder();
        var draft = p.draft();
        var documents = new ArrayList<>(row.sources());
        documents.add(order);
        var codes = documents.stream().map(LogisticsCostSourceView::documentNo).distinct().toList();
        platform.saveDraft(
                tenant,
                row.id(),
                new Draft(
                        row.revision(),
                        "DELIVERY",
                        LocalDate.parse(o.useTime().substring(0, 10)),
                        "HLL",
                        o.raw().get("运单号"),
                        o.orderNo(),
                        draft == null ? null : draft.warehouseCode(),
                        draft == null ? null : draft.senderName(),
                        draft == null ? null : draft.senderContact(),
                        draft == null ? null : draft.senderPhone(),
                        draft == null ? null : draft.senderAddress(),
                        draft == null ? order.cityCode() : draft.cityCode(),
                        o.facts() == null ? null : o.facts().billAmount(),
                        draft == null ? null : draft.ownerEmployeeCode(),
                        draft == null ? null : draft.remark(),
                        "订货宝原销售单自动关联",
                        draft == null ? null : draft.zeroReason(),
                        codes,
                        row.data().requestId(),
                        row.data().paymentStatusCode(),
                        List.of()),
                documents,
                actor);
    }

    private List<String> codes(LogisticsCostDetailView row) {
        return row.data().ledger() == null
                ? row.sources().stream().map(LogisticsCostSourceView::documentNo).toList()
                : row.data().ledger().sources().stream()
                        .map(LogisticsCostLedger.Source::orderCode)
                        .filter(Objects::nonNull)
                        .toList();
    }

    private LogisticsCostCommand withRequest(
            LogisticsCostCommand c, String request, Integer revision, LogisticsCostLedger ledger) {
        return new LogisticsCostCommand(
                request,
                revision,
                c.title(),
                c.typeCode(),
                c.cityCode(),
                c.cityName(),
                c.departmentId(),
                c.ownerEmployeeCode(),
                c.costDate(),
                c.amount(),
                c.paymentStatusCode(),
                c.logistics(),
                c.remark(),
                c.reason(),
                ledger);
    }

    private void changed(
            String tenant, String id, String actor, String action, String reason, Object snapshot) {
        jdbc.update(
                "UPDATE order_logistics_cost_guard SET version=version+1 WHERE tenant_id=?",
                tenant);
        jdbc.update(
                "INSERT INTO"
                    + " order_logistics_cost_audit(tenant_id,entry_id,action_code,actor_id,reason,snapshot_json)"
                    + " VALUES(?,?,?,?,?,?)",
                tenant,
                id,
                action,
                actor,
                reason,
                json.writeValueAsString(snapshot));
    }

    @Override
    public Map<String, List<Source>> byCosts(String tenant, List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var params = new ArrayList<Object>();
        params.add(tenant);
        params.addAll(ids);
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM order_transport_shipment_source WHERE tenant_id=? AND"
                                + " cost_id IN ("
                                + String.join(",", Collections.nCopies(ids.size(), "?"))
                                + ") ORDER BY updated_time DESC",
                        params.toArray());
        Map<String, List<Source>> result = new LinkedHashMap<>();
        for (var row : rows)
            result.computeIfAbsent(Objects.toString(row.get("cost_id")), k -> new ArrayList<>())
                    .add(source(row));
        return result;
    }

    private Source source(Map<String, Object> row) {
        return new Source(
                row.get("id").toString(),
                Objects.toString(row.get("cost_id"), null),
                json.readValue(row.get("latest_json").toString(), Command.class),
                Objects.toString(row.get("issue"), null),
                ((Number) row.get("difference_pending")).intValue() != 0);
    }

    private String fingerprint(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String first(String a, String b) {
        return a == null ? b : a;
    }

    private static BusinessException bad(String s) {
        return new BusinessException(ErrorCode.BAD_REQUEST, s, List.of());
    }
}
