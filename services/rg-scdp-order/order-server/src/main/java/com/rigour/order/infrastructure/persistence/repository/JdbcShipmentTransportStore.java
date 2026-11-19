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
    private static final String CARRIER_ISSUE = "来源物流公司尚未映射";
    private static final String DOOR_ISSUE = "订货宝仅标记送货上门";
    private static final String DELIVERY_MODE_ISSUE = "来源配送方式尚未映射";
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
                            + " platform_code='DHB' AND connector_id=? AND (source_shipment_id=? OR"
                            + " source_shipment_no=?) FOR UPDATE",
                        tenant,
                        command.connectorId().toString(),
                        command.shipmentId(),
                        command.shipmentNo());
        // 单笔补查可能只有发货单号，不能把它当作另一个发货身份重新建运输。
        // 同号已有多个成本或两个正式来源ID冲突时先保留现场，不猜选或覆盖任一金额。
        if (records.size() > 1) throw bad("该发货单已对应多条运输记录，请核对重复记录");
        String channel = transport.ledger().transportMethodCode();
        Map<String, Object> old = records.isEmpty() ? null : records.getFirst();
        if (old != null) {
            String priorId = old.get("source_shipment_id").toString();
            if (!priorId.equals(command.shipmentId())
                    && !priorId.equals(command.shipmentNo())
                    && !command.shipmentId().equals(command.shipmentNo()))
                throw bad("该发货单的来源编号不一致，请核对原单");
            var previous = json.readValue(old.get("latest_json").toString(), Command.class);
            if (previous.sourceUpdatedAt() != null
                    && command.sourceUpdatedAt() != null
                    && command.sourceUpdatedAt().isBefore(previous.sourceUpdatedAt()))
                return source(old);
            if (command.carrierCode() == null && previous.carrierCode() != null
                    && !"SELF".equals(command.deliveryModeCode())
                    && previous.carrierName() != null
                    && Objects.equals(previous.carrierName(), command.carrierName())
                    && Objects.equals(previous.deliveryModeName(), command.deliveryModeName())
                    && Objects.equals(sourceCarrierCode(previous), sourceCarrierCode(command))) {
                String mode = first(command.deliveryModeCode(), previous.deliveryModeCode());
                command = command.withCarrierClassification(previous.carrierCode(), mode);
                transport = transport.withLedger(
                        transport.ledger().withCarrierClassification(previous.carrierCode(), mode),
                        transport.amount());
            }
        }
        String body = json.writeValueAsString(command);
        String fp = fingerprint(channel == null ? body : body + "\n" + channel);
        if (old != null && fp.equals(old.get("fingerprint")) && old.get("issue") == null) return source(old);
        String sourceId = old == null ? UUID.randomUUID().toString() : old.get("id").toString();
        String costId = old == null ? null : Objects.toString(old.get("cost_id"), null);
        // 仅对“无单号、无运费、无人工凭证”的旧记录清理允许补单号后重新建有效运输。
        // 普通人工作废记录不得被来源同步重新启用。
        if (old != null && costId != null && !command.trackingNumbers().isEmpty()) {
            var priorSource = json.readValue(old.get("latest_json").toString(), Command.class);
            if (priorSource.trackingNumbers().isEmpty()) {
                Integer archived =
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_logistics_cost c WHERE c.tenant_id=?"
                                    + " AND c.id=? AND c.status_code='VOID' AND c.amount IS NULL"
                                    + " AND EXISTS(SELECT 1 FROM order_logistics_cost_audit h WHERE"
                                    + " h.tenant_id=c.tenant_id AND h.entry_id=c.id AND"
                                    + " h.action_code='VOID' AND"
                                    + " h.reason='订货宝无快递单号旧记录清理：按2026-11-16确认规则作废') AND NOT"
                                    + " EXISTS(SELECT 1 FROM order_logistics_cost_attachment a"
                                    + " WHERE a.tenant_id=c.tenant_id AND a.entry_id=c.id AND"
                                    + " a.deleted=0)",
                                Integer.class,
                                tenant,
                                costId);
                if (archived != null && archived == 1) costId = null;
            }
        }
        String issue = null;
        boolean difference = false;
        boolean shipped = Set.of("receivedin", "received").contains(command.sourceStatus());
        if (shipped && costId == null) {
            var matches = matches(tenant, command, transport.ledger().carrierCode());
            if (matches.size() > 1) issue = "同一快递单号对应多笔运输，请核对已有记录";
            else if (matches.size() == 1 && order == null) issue = "同一快递单号已有运输记录，请核对后关联原销售单";
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
                else if (!codes.contains(order.documentNo()) && !"ACTIVE".equals(row.statusCode()))
                    issue = "匹配运输已作废，请先核对原记录，不能自动变更关联";
                else {
                    costId = row.id();
                    if (!codes.contains(order.documentNo()))
                        linkPlatform(tenant, actor, row, order, transport);
                }
            } else {
                costId = UUID.randomUUID().toString();
                var data = withRequest(transport, costId, null, transport.ledger());
                var linked =
                        order == null
                                ? data.ledger()
                                : data.ledger()
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
                            + " VALUES(?,?,?,?,'LOGISTICS',?,'DELIVERY','DELIVERY',?,?,?,?,NULL,'UNKNOWN','ACTIVE',?,?,?,?,'DHB',?,?,?,?)",
                        costId,
                        tenant,
                        CostDocumentNumber.next(),
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
                        old.get("sales_order_id") == null
                                ? null
                                : ((Number) old.get("sales_order_id")).longValue(),
                        command.salesOrderId())
                && old.get("sales_order_id") != null
                && command.salesOrderId() != null) {
            issue = "来源发货更换了销售订单，请核对原运输关联";
            shipped = false;
        }
        if (costId != null
                && old != null
                && !(command.salesOrderId() == null && old.get("sales_order_id") != null)) {
            var states =
                    jdbc.queryForList(
                            "SELECT status_code,revision,deleted,source_system,data_json FROM"
                                    + " order_logistics_cost WHERE tenant_id=? AND id=? FOR UPDATE",
                            tenant,
                            costId);
            if (!states.isEmpty()) {
                var state = states.getFirst();
                difference = !shipped || "VOID".equals(state.get("status_code"));
                if (!shipped && issue == null)
                    issue =
                            "cancelled".equals(command.sourceStatus())
                                    ? "来源发货已取消，请核对运输记录"
                                    : "来源仍为待发货，请核对实际发货情况";
                else if ("ACTIVE".equals(state.get("status_code"))
                        && !JdbcFlags.isSet(state.get("deleted"))
                        && "DHB".equals(state.get("source_system"))) {
                    var prior =
                            json.readValue(
                                    state.get("data_json").toString(), LogisticsCostCommand.class);
                    // 新来源只更新收发货事实；人工补充的账单、付款、备注、凭证和费用分摊保留。
                    var nextLedger = transport.ledger();
                    var oldLedger = prior.ledger();
                    String sourceChannel = channel == null ? oldLedger.transportMethodCode() : channel;
                    boolean linkedNow =
                            oldLedger.sources().isEmpty()
                                    && !nextLedger.sources().isEmpty()
                                    && order != null;
                    var preservedSources =
                            linkedNow
                                    ? nextLedger.sources().stream()
                                            .map(
                                                    l ->
                                                            l.withBusinessSnapshot(
                                                                    order.documentAmount(),
                                                                    order.documentDate(),
                                                                    Instant.now()))
                                            .toList()
                                    : oldLedger.sources();
                    boolean factsChanged =
                            linkedNow
                                    || !Objects.equals(prior.costDate(), transport.costDate())
                                    || !Objects.equals(
                                            oldLedger.carrierCode(), nextLedger.carrierCode())
                                    || !Objects.equals(
                                            oldLedger.deliveryModeCode(),
                                            nextLedger.deliveryModeCode())
                                    || !Objects.equals(oldLedger.transportMethodCode(), sourceChannel)
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
                                    sourceChannel,
                                    nextLedger.sender(),
                                    nextLedger.receivingPoints(),
                                    oldLedger.feeItems(),
                                    preservedSources,
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
                    if (linkedNow)
                        // 金额未变也须为新关联生成分摊；不能命中 reprice 的同额直接返回。
                        data =
                                com.rigour.order.application.service.cost.TransportAllocationPlan
                                        .reprice(data.withLedger(data.ledger(), null), prior.amount());
                    if (factsChanged) {
                        jdbc.update(
                                "UPDATE order_logistics_cost SET"
                                    + " data_json=?,sources_json=?,carrier_code=?,delivery_mode_code=?,cost_date=?,city_code=?,revision=revision+1,updated_by=?,updated_time=CURRENT_TIMESTAMP(6)"
                                    + " WHERE tenant_id=? AND id=?",
                                json.writeValueAsString(data),
                                json.writeValueAsString(data.ledger().sources()),
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
            issue = first(issue, "送货上门".equals(command.deliveryModeName())
                    ? "订货宝仅标记送货上门，请核对发货单的物流公司后重新同步"
                    : "来源配送方式尚未映射，请维护数据字典的来源映射后重新同步");
        if (shipped
                && transport.ledger().carrierCode() == null
                && !"SELF".equals(transport.ledger().deliveryModeCode()))
            issue = first(issue, CARRIER_ISSUE + "到数据字典，请维护映射");
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
                        + " source_shipment_id=?,source_shipment_no=?,sales_order_id=?,cost_id=?,latest_json=?,fingerprint=?,issue=?,difference_pending=?,updated_by=?,updated_time=CURRENT_TIMESTAMP(6)"
                        + " WHERE tenant_id=? AND id=?",
                    old.get("source_shipment_id").equals(old.get("source_shipment_no"))
                            ? command.shipmentId()
                            : old.get("source_shipment_id"),
                    command.shipmentNo(),
                    command.salesOrderId() != null
                            ? command.salesOrderId()
                            : old.get("sales_order_id"),
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
            LogisticsCostSourceView order,
            LogisticsCostCommand shipment) {
        var p = platform.detail(tenant, row.id());
        if (p == null) throw bad("匹配运输未关联当前销售单，请核对后手工关联");
        var o = p.source().latestOrder() == null ? p.source().order() : p.source().latestOrder();
        var draft = p.draft();
        var documents = new ArrayList<>(row.sources());
        documents.add(order);
        var codes = documents.stream().map(LogisticsCostSourceView::documentNo).distinct().toList();
        var draftToSave =
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
                        row.data().amount(),
                        draft == null ? null : draft.ownerEmployeeCode(),
                        draft == null ? null : draft.remark(),
                        "订货宝原销售单自动关联",
                        draft == null ? null : draft.zeroReason(),
                        codes,
                        row.data().requestId(),
                        row.data().paymentStatusCode(),
                        List.of());
        // 同一货拉拉运输补上订货宝原单时，保存完整成本分摊，不退回仅有关系的中间状态。
        var l = shipment.ledger();
        var ledger =
                new LogisticsCostLedger(
                                2,
                                "DELIVERY",
                                "THIRD_PARTY",
                                "HLL",
                                row.data().costDate().toString().substring(0, 7),
                                null,
                                null,
                                l.sender(),
                                l.receivingPoints(),
                                l.feeItems(),
                                l.sources(),
                                null,
                                "实际无运单号",
                                null,
                                l.scopeWarehouseIds(),
                                l.scopeCityCodes())
                        .withPlatformOrderNo(o.orderNo());
        var data = row.data();
        var next =
                new LogisticsCostCommand(
                        data.requestId(),
                        row.revision(),
                        "销售订单配送",
                        "DELIVERY",
                        shipment.cityCode(),
                        shipment.cityName(),
                        data.departmentId(),
                        data.ownerEmployeeCode(),
                        data.costDate(),
                        null,
                        data.paymentStatusCode(),
                        data.logistics(),
                        data.remark(),
                        "订货宝原销售单自动关联",
                        ledger);
        next =
                com.rigour.order.application.service.cost.TransportAllocationPlan.reprice(
                        next, data.amount());
        costs.save(
                tenant,
                "LOGISTICS",
                row.id(),
                next,
                List.of(),
                row.warehouseName(),
                row.ownerEmployeeName(),
                actor,
                "order:logistics-cost:read");
        platform.saveAssociations(tenant, row.id(), draftToSave, documents, actor);
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
    public int backfillCarrier(String tenant, String actor, CarrierBackfill command) {
        platform.lockTenant(tenant);
        // 跳过已作废、已改为自配送或已填其他公司的运输单据。历史来源和附件继续挂原 ID。
        var rows = jdbc.queryForList("""
                SELECT s.id,s.cost_id,s.issue,c.carrier_code,c.delivery_mode_code,
                    s.latest_json->>'$.deliveryModeName' AS source_mode
                FROM order_transport_shipment_source s
                JOIN order_logistics_cost c ON c.tenant_id=s.tenant_id AND c.id=s.cost_id
                    AND c.source_system='DHB' AND c.status_code='ACTIVE' AND c.deleted=0
                WHERE s.tenant_id=? AND s.platform_code='DHB'
                    AND s.latest_json->>'$.sourceStatus' IN ('receivedin','received')
                    AND (c.delivery_mode_code IS NULL OR c.delivery_mode_code='THIRD_PARTY')
                    AND (c.carrier_code IS NULL
                        OR (c.carrier_code=? AND (
                            s.issue LIKE ? OR s.issue LIKE ?
                            OR (s.issue LIKE ? AND s.latest_json->>'$.deliveryModeName' IN ('快递','送货上门'))
                        )))
                    AND JSON_TYPE(JSON_EXTRACT(c.data_json,'$.ledger'))='OBJECT'
                    AND COALESCE(NULLIF(c.data_json->>'$.ledger.carrierCode','null'),?)=?
                    AND COALESCE(NULLIF(s.latest_json->>'$.carrierCode','null'),?)=?
                    AND NOT EXISTS (
                        SELECT 1 FROM order_transport_shipment_source other
                        WHERE other.tenant_id=s.tenant_id AND other.cost_id=s.cost_id
                            AND other.id<>s.id AND other.latest_json->>'$.sourceStatus' IN ('receivedin','received')
                            AND (
                                COALESCE(NULLIF(other.latest_json->>'$.carrierCode','null'),?)<>?
                                OR (NULLIF(other.latest_json->>'$.carrierCode','null') IS NULL
                                    AND NULLIF(other.latest_json->>'$.carrierName','null') IS NOT NULL
                                    AND BINARY other.latest_json->>'$.carrierName'<>s.latest_json->>'$.carrierName')
                            )
                    )
                    AND BINARY COALESCE(
                        NULLIF(NULLIF(TRIM(s.latest_json->>'$.sourceFields.detail.logistics_code'),''),'null'),
                        NULLIF(NULLIF(TRIM(s.latest_json->>'$.sourceFields.list.logistics_code'),''),'null'),
                        NULLIF(NULLIF(TRIM(s.latest_json->>'$.carrierName'),''),'null'))=?
                ORDER BY s.id LIMIT ? FOR UPDATE
                """, tenant, command.carrierCode(), CARRIER_ISSUE + "%", DOOR_ISSUE + "%",
                DELIVERY_MODE_ISSUE + "%", command.carrierCode(), command.carrierCode(),
                command.carrierCode(), command.carrierCode(),
                command.carrierCode(), command.carrierCode(), command.sourceValue(), CarrierBackfill.BATCH_SIZE);
        var updatedCosts = new HashSet<String>();
        for (var row : rows) {
            String id = row.get("id").toString();
            String costId = row.get("cost_id").toString();
            String oldMode = Objects.toString(row.get("delivery_mode_code"), null);
            boolean knownDeliveryMode = Set.of("送货上门", "快递").contains(
                    Objects.toString(row.get("source_mode"), ""));
            String mode = oldMode == null && knownDeliveryMode ? "THIRD_PARTY" : oldMode;
            if ((row.get("carrier_code") == null || !Objects.equals(mode, oldMode))
                    && updatedCosts.add(costId)) {
                jdbc.update("""
                        UPDATE order_logistics_cost
                        SET carrier_code=?,delivery_mode_code=?,
                            data_json=JSON_SET(data_json,'$.ledger.carrierCode',?,
                                '$.ledger.deliveryModeCode',?),revision=revision+1,
                            updated_by=?,updated_time=CURRENT_TIMESTAMP(6)
                        WHERE tenant_id=? AND id=?
                        """, command.carrierCode(), mode, command.carrierCode(), mode, actor, tenant, costId);
                jdbc.update("""
                        INSERT INTO order_logistics_cost_audit
                            (tenant_id,entry_id,action_code,actor_id,reason,snapshot_json)
                        SELECT tenant_id,id,'SOURCE_SYNC',?,'按数据字典补齐订货宝物流公司',data_json
                        FROM order_logistics_cost WHERE tenant_id=? AND id=?
                        """, actor, tenant, costId);
            }
            String issue = Objects.toString(row.get("issue"), null);
            if (issue != null && (issue.startsWith(CARRIER_ISSUE) || issue.startsWith(DOOR_ISSUE)
                    || (knownDeliveryMode && issue.startsWith(DELIVERY_MODE_ISSUE))))
                issue = null;
            jdbc.update("""
                    UPDATE order_transport_shipment_source
                    SET latest_json=JSON_SET(latest_json,'$.carrierCode',?,
                        '$.deliveryModeCode',COALESCE(?,NULLIF(latest_json->>'$.deliveryModeCode','null'))),
                        issue=?,updated_by=?,updated_time=CURRENT_TIMESTAMP(6)
                    WHERE tenant_id=? AND id=?
                    """, command.carrierCode(), mode, issue, actor, tenant, id);
        }
        if (!updatedCosts.isEmpty())
            jdbc.update("UPDATE order_logistics_cost_guard SET version=version+1 WHERE tenant_id=?", tenant);
        return rows.size();
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

    private static String sourceCarrierCode(Command command) {
        for (String part : List.of("detail", "list")) {
            if (command.sourceFields().get(part) instanceof Map<?, ?> payload) {
                String value = Objects.toString(payload.get("logistics_code"), "").strip();
                if (!value.isEmpty()) return value;
            }
        }
        return null;
    }

    private Source source(Map<String, Object> row) {
        return new Source(
                row.get("id").toString(),
                Objects.toString(row.get("cost_id"), null),
                json.readValue(row.get("latest_json").toString(), Command.class),
                Objects.toString(row.get("issue"), null),
                JdbcFlags.isSet(row.get("difference_pending")));
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
