package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.order.api.v1.model.LogisticsCostActionCommand;
import com.rigour.order.api.v1.model.LogisticsCostAuditView;
import com.rigour.order.api.v1.model.LogisticsCostCommand;
import com.rigour.order.api.v1.model.LogisticsCostDetailView;
import com.rigour.order.api.v1.model.LogisticsCostLedger;
import com.rigour.order.api.v1.model.LogisticsCostPageView;
import com.rigour.order.api.v1.model.LogisticsCostQuery;
import com.rigour.order.api.v1.model.LogisticsCostSourceView;
import com.rigour.order.application.port.out.OrderLogisticsCostStore;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.order.domain.cost.LogisticsCostRules;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostAuditEntity;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostEntity;
import com.rigour.order.infrastructure.persistence.mapper.OrderLogisticsCostAuditMapper;
import com.rigour.order.infrastructure.persistence.mapper.OrderLogisticsCostMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class MybatisPlusOrderLogisticsCostRepository implements OrderLogisticsCostStore {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rigour.order.application.port.out.PlatformTransportStore platformRecords;

    private final OrderLogisticsCostMapper mapper;
    private final OrderLogisticsCostAuditMapper audits;
    private final ObjectMapper json;
    private final LogisticsWarehouseDataScope scope;
    private final OrderLogisticsReferences references;

    public MybatisPlusOrderLogisticsCostRepository(
            OrderLogisticsCostMapper mapper,
            OrderLogisticsCostAuditMapper audits,
            ObjectMapper json,
            LogisticsWarehouseDataScope scope,
            OrderLogisticsReferences references) {
        this.mapper = mapper;
        this.audits = audits;
        this.json = json;
        this.scope = scope;
        this.references = references;
    }

    private void lock(String tenant) {
        mapper.ensureGuard(tenant);
        mapper.lock(tenant);
        // 与费用写入处于同一事务；失败回滚，确认、更正及作废均推进分析版本。
        mapper.advanceAnalyticsVersion(tenant);
    }

    private QueryWrapper<OrderLogisticsCostEntity> where(
            String tenant, String category, String action) {
        var q =
                new QueryWrapper<OrderLogisticsCostEntity>()
                        .eq("tenant_id", tenant)
                        .eq("category_code", category)
                        .eq("deleted", 0);
        var p = scope.predicate(action, "warehouse_id", "source_warehouse_id");
        var manual = scope.manualPredicate(action, "INVENTORY");
        q.and(
                w ->
                        w.and(
                                        m -> {
                                            m.isNull("warehouse_id").isNull("source_warehouse_id");
                                            bind(m, manual.sql(), manual.args());
                                        })
                                .or(
                                        m -> {
                                            m.isNotNull("warehouse_id")
                                                    .isNotNull("source_warehouse_id");
                                            bind(m, p.sql(), p.args());
                                        }));
        scope.applyCity(q, action, "city_code");
        var ledgerScope = scope.ledgerPredicate(action);
        if (ledgerScope != null && !"1=1".equals(ledgerScope.sql()))
            bind(
                    q,
                    "(COALESCE(JSON_EXTRACT(data_json,'$.ledger.schemaVersion'),1)<>2 OR "
                            + ledgerScope.sql()
                            + ")",
                    ledgerScope.args());
        return q;
    }

    /**
     * Batch source references under the same tenant, city and complete warehouse policy as the cost
     * list.
     */
    public java.util.Map<String, Integer> sourceUsage(
            String tenant, java.util.List<String> documents, String action) {
        if (documents.isEmpty()) return java.util.Map.of();
        var q = where(tenant, "LOGISTICS", action).ne("status_code", "VOID").select("sources_json");
        q.and(
                w -> {
                    for (String document : documents)
                        w.or().apply(
                                        "JSON_SEARCH(sources_json,'one',{0},NULL,'$[*].orderCode','$[*].documentNo')"
                                            + " IS NOT NULL",
                                        document.replace("\\", "\\\\")
                                                .replace("%", "\\%")
                                                .replace("_", "\\_"));
                });
        var result = new java.util.HashMap<String, Integer>();
        var keys = new java.util.HashSet<>(documents);
        for (var row : mapper.selectMaps(q)) {
            var counted = new java.util.HashSet<String>();
            for (var source :
                    json.readTree(java.util.Objects.toString(row.get("sources_json"), "[]"))) {
                String code =
                        source.path("orderCode").asString(source.path("documentNo").asString(""));
                if (keys.contains(code)) counted.add(code);
            }
            counted.forEach(code -> result.merge(code, 1, Integer::sum));
        }
        return result;
    }

    private static void partyFilter(
            QueryWrapper<OrderLogisticsCostEntity> q, String field, String value) {
        // field is a fixed internal path; user text is always bound and JSON_SEARCH wildcards are
        // escaped.
        String ledgerPath =
                "senderName".equals(field) ? "$.ledger.sender" : "$.ledger.receivingPoints[*]";
        String pattern =
                "%"
                        + value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                        + "%";
        q.apply(
                "(LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics."
                        + field
                        + "')),''))>0"
                        + " OR JSON_SEARCH(sources_json,'one',{1},NULL,'$[*]."
                        + field
                        + "') IS NOT NULL OR JSON_SEARCH(data_json,'one',{1},NULL,'"
                        + ledgerPath
                        + ".manualName','"
                        + ledgerPath
                        + ".partyCode') IS NOT NULL OR"
                        + " JSON_SEARCH(data_json,'one',{1},NULL,'$.logistics.externalDocuments[*]."
                        + field
                        + "') IS NOT NULL)",
                value.trim(),
                pattern);
    }

    private QueryWrapper<OrderLogisticsCostEntity> query(
            String tenant, String category, LogisticsCostQuery filter, String action) {
        var q = where(tenant, category, action);
        if (!"PURCHASE".equals(filter.typeCode()))
            q.and(w -> w.isNull("type_code").or().ne("type_code", "PURCHASE"));
        q.eq(text(filter.costMonth()), "cost_month", filter.costMonth());
        q.eq(text(filter.carrierCode()), "carrier_code", filter.carrierCode());
        if (text(filter.orderTypeCode()))
            q.apply(
                    "(JSON_SEARCH(sources_json,'one',{0},NULL,'$[*].orderTypeCode') IS NOT NULL OR"
                            + " JSON_SEARCH(sources_json,'one',{1},NULL,'$[*].sourceType') IS NOT"
                            + " NULL)",
                    filter.orderTypeCode(),
                    switch (filter.orderTypeCode()) {
                        case "PURCHASE_ORDER" -> "PURCHASE";
                        case "TRANSFER_ORDER" -> "TRANSFER";
                        case "SALES_ORDER" -> "DELIVERY";
                        default -> filter.orderTypeCode();
                    });
        if ("MANUAL_ENTRY".equals(filter.sourceSystem())) q.ne("source_system", "HUOLALA");
        else q.eq(text(filter.sourceSystem()), "source_system", filter.sourceSystem());
        if ("LINKED".equals(filter.relationState()) || "UNLINKED".equals(filter.relationState())) {
            String condition =
                    "(EXISTS(SELECT 1 FROM order_logistics_import_order i JOIN"
                        + " order_logistics_transport_link l ON l.tenant_id=i.tenant_id AND"
                        + " l.source_id=i.id WHERE i.tenant_id=order_logistics_cost.tenant_id AND"
                        + " i.cost_id=order_logistics_cost.id) OR JSON_LENGTH(sources_json)>0)";
            q.apply(("UNLINKED".equals(filter.relationState()) ? "NOT " : "") + condition);
        }
        String sourceExists =
                "EXISTS(SELECT 1 FROM order_logistics_import_order i WHERE"
                        + " i.tenant_id=order_logistics_cost.tenant_id AND"
                        + " i.cost_id=order_logistics_cost.id AND i.source_channel='API' AND ";
        if (text(filter.platformOrderNo()))
            q.apply(
                    "("
                            + sourceExists
                            + "LOCATE({0},i.carrier_order_no)>0) OR"
                            + " LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.ledger.platformOrderNo')),''))>0)",
                    filter.platformOrderNo().strip());
        if (text(filter.platformEmployeeName()))
            q.apply(
                    sourceExists
                            + "LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(i.latest_source_json,i.source_json),'$.facts.sourceEmployeeName')),''))>0)",
                    filter.platformEmployeeName().strip());
        if (text(filter.mappedEmployeeCode()))
            q.apply(
                    sourceExists
                            + "JSON_UNQUOTE(JSON_EXTRACT(COALESCE(i.latest_source_json,i.source_json),'$.facts.mappedEmployeeCode'))={0})",
                    filter.mappedEmployeeCode());
        if (text(filter.employeeRelationState()))
            q.apply(
                    sourceExists
                            + "(COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(i.latest_source_json,i.source_json),'$.facts.mappedEmployeeCode')),'null'),'')"
                            + ("LINKED".equals(filter.employeeRelationState()) ? "<>''" : "=''")
                            + ")");
        if (text(filter.contactPhone()))
            q.apply(
                    "("
                            + sourceExists
                            + "JSON_SEARCH(COALESCE(i.latest_source_json,i.source_json),'one',{0},NULL,'$.facts.stops[*].contactPhone')"
                            + " IS NOT NULL) OR"
                            + " JSON_SEARCH(data_json,'one',{0},NULL,'$.ledger.sender.phone','$.ledger.receivingPoints[*].phone')"
                            + " IS NOT NULL)",
                    "%"
                            + filter.contactPhone().trim().replace("%", "\\%").replace("_", "\\_")
                            + "%");
        if (Boolean.TRUE.equals(filter.missingAmount()))
            q.isNull("amount").ne("status_code", "VOID");
        q.eq(text(filter.payerCode()), "payer_code", filter.payerCode());
        if (text(filter.objectName())) {
            String objectPattern =
                    "%"
                            + filter.objectName()
                                    .trim()
                                    .replace("\\", "\\\\")
                                    .replace("%", "\\%")
                                    .replace("_", "\\_")
                            + "%";
            q.apply(
                    "(LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.senderName')),''))>0"
                        + " OR LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.receiverName')),''))>0"
                        + " OR JSON_SEARCH(sources_json,'one',{1},NULL,'$[*].senderName') IS NOT"
                        + " NULL OR JSON_SEARCH(sources_json,'one',{1},NULL,'$[*].receiverName') IS"
                        + " NOT NULL OR LOCATE({0},document_no)>0 OR"
                        + " LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.carrierName')),''))>0"
                        + " OR LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.trackingNo')),''))>0"
                        + " OR JSON_SEARCH(sources_json,'one',{1},NULL,'$[*].documentNo','$[*].originalNo')"
                        + " IS NOT NULL OR"
                        + " JSON_SEARCH(data_json,'one',{1},NULL,'$.logistics.externalDocuments[*].documentNo','$.logistics.externalDocuments[*].receiverName')"
                        + " IS NOT NULL)",
                    filter.objectName().trim(), objectPattern);
        }
        // Filter the frozen business-document ownership, never the booking employee's current HR
        // department.
        for (var field :
                java.util.Map.of(
                                "employeeName",
                                java.util.Objects.toString(filter.sourceEmployeeName(), ""),
                                "departmentName",
                                java.util.Objects.toString(filter.sourceDepartmentName(), ""))
                        .entrySet()) {
            if (!text(field.getValue())) continue;
            String pattern =
                    "%"
                            + field.getValue()
                                    .trim()
                                    .replace("\\", "\\\\")
                                    .replace("%", "\\%")
                                    .replace("_", "\\_")
                            + "%";
            q.apply(
                    "JSON_SEARCH(data_json,'one',{0},NULL,'$.ledger.sources[*]."
                            + field.getKey()
                            + "') IS NOT NULL",
                    pattern);
        }
        if (text(filter.senderName())) partyFilter(q, "senderName", filter.senderName());
        if (text(filter.receiverName())) partyFilter(q, "receiverName", filter.receiverName());
        if (text(filter.carrierName()))
            q.apply(
                    "LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.carrierName')),''))>0",
                    filter.carrierName().trim());
        // External source numbers remain distinguishable from local source IDs.
        if (text(filter.documentNo()))
            q.apply("LOCATE({0},document_no)>0", filter.documentNo().trim());
        if (text(filter.title())) q.apply("LOCATE({0},title)>0", filter.title().trim());
        if (text(filter.remark()))
            q.apply(
                    "LOCATE({0},JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.remark')))>0",
                    filter.remark().trim());
        if (text(filter.trackingNo()))
            q.apply(
                    "LOCATE({0},COALESCE(tracking_no,JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.trackingNo')),''))>0",
                    filter.trackingNo().trim());
        if (text(filter.sourceDocumentNo())) {
            // 只匹配来源单号，避免命中来源快照中的城市、仓库等其他字段。
            String value =
                    filter.sourceDocumentNo()
                            .trim()
                            .replace("\\", "\\\\")
                            .replace("%", "\\%")
                            .replace("_", "\\_");
            q.apply(
                    "(JSON_SEARCH(sources_json,'one',{0},NULL,'$[*].originalNo') IS NOT NULL OR"
                        + " JSON_SEARCH(sources_json,'one',{0},NULL,'$[*].documentNo','$[*].orderCode')"
                        + " IS NOT NULL OR"
                        + " JSON_SEARCH(data_json,'one',{0},NULL,'$.logistics.externalDocuments[*].documentNo')"
                        + " IS NOT NULL)",
                    "%" + value + "%");
        }
        q.in("OTHER".equals(filter.typeCode()), "type_code", List.of("OTHER", "NON_SALES"))
                .eq(
                        text(filter.typeCode()) && !"OTHER".equals(filter.typeCode()),
                        "type_code",
                        filter.typeCode())
                .eq(text(filter.cityCode()), "city_code", filter.cityCode())
                .eq(text(filter.statusCode()), "status_code", filter.statusCode())
                .eq(
                        text(filter.paymentStatusCode()),
                        "payment_status_code",
                        filter.paymentStatusCode())
                .eq(filter.warehouseId() != null, "warehouse_id", filter.warehouseId())
                .eq(
                        text(filter.ownerEmployeeCode()),
                        "owner_employee_code",
                        filter.ownerEmployeeCode())
                .ge(filter.dateFrom() != null, "cost_date", filter.dateFrom())
                .le(filter.dateTo() != null, "cost_date", filter.dateTo());
        return q;
    }

    @Transactional(readOnly = true)
    public LogisticsCostPageView entries(
            String tenant, String category, LogisticsCostQuery filter, String action) {
        var totals =
                mapper.selectMaps(
                                query(tenant, category, filter, action)
                                        .select(
                                                "COUNT(*) total",
                                                "COALESCE(SUM(CASE WHEN status_code='CONFIRMED'"
                                                        + " THEN amount ELSE 0"
                                                        + " END),0) confirmed",
                                                "COALESCE(SUM(CASE WHEN status_code='CONFIRMED' AND"
                                                    + " payer_code='COMPANY' AND"
                                                    + " payment_status_code='PAID' THEN amount ELSE"
                                                    + " 0 END),0) paid",
                                                "COALESCE(SUM(status_code='DRAFT'),0) pending",
                                                "COALESCE(SUM(CASE WHEN status_code<>'VOID' THEN"
                                                        + " amount ELSE 0 END),0) registered",
                                                "COALESCE(SUM(status_code<>'VOID' AND amount IS"
                                                        + " NULL),0) missing",
                                                "COALESCE(SUM(CASE WHEN status_code='DRAFT' THEN"
                                                        + " amount ELSE 0 END),0) pending_amount",
                                                "COALESCE(SUM(status_code='CONFIRMED' AND amount=0"
                                                        + " AND payer_code IN"
                                                        + " ('SUPPLIER','CUSTOMER','OTHER')),0)"
                                                        + " third_party"))
                        .getFirst();
        var rows =
                mapper
                        .selectList(
                                query(tenant, category, filter, action)
                                        .orderByDesc("cost_date", "id")
                                        .last(
                                                "LIMIT "
                                                        + filter.step()
                                                        + " OFFSET "
                                                        + filter.begin()))
                        .stream()
                        .map(this::view)
                        .toList();
        return new LogisticsCostPageView(
                ((Number) totals.get("total")).longValue(),
                filter.begin(),
                filter.step(),
                new BigDecimal(totals.get("confirmed").toString()),
                new BigDecimal(totals.get("paid").toString()),
                ((Number) totals.get("pending")).longValue(),
                rows,
                new BigDecimal(totals.get("registered").toString()),
                ((Number) totals.get("missing")).longValue(),
                new BigDecimal(totals.get("pending_amount").toString()),
                ((Number) totals.get("third_party")).longValue());
    }

    public LogisticsCostDetailView entry(String tenant, String category, String id, String action) {
        var row = mapper.selectOne(where(tenant, category, action).eq("id", id));
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "费用不存在或无访问权限", List.of());
        return view(row);
    }

    @Transactional(readOnly = true)
    public List<LogisticsCostDetailView> selectedEntries(
            String tenant, List<String> ids, String action) {
        // 勾选导出与列表使用相同租户、仓库、城市及整单范围，不能退回普通详情权限。
        return mapper.selectList(where(tenant, "LOGISTICS", action).in("id", ids)).stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public LogisticsCostDetailView save(
            String tenant,
            String category,
            String id,
            LogisticsCostCommand c,
            List<LogisticsCostSourceView> sources,
            String warehouseName,
            String employeeName,
            String actor,
            String action) {
        lock(tenant);
        var previous =
                id == null
                        ? mapper.selectOne(
                                new QueryWrapper<OrderLogisticsCostEntity>()
                                        .eq("tenant_id", tenant)
                                        .eq("request_id", c.requestId()))
                        : null;
        var old =
                id != null
                        ? entry(tenant, category, id, action)
                        : previous == null
                                ? null
                                : entry(tenant, category, previous.getId(), action);
        if (platformRecords != null)
            platformRecords.assertReadOnly(
                    tenant, id != null ? id : previous == null ? null : previous.getId(), c);
        boolean platformSource =
                id != null
                        && platformRecords != null
                        && platformRecords.byCosts(tenant, List.of(id)).containsKey(id);
        boolean ledger = c.ledger() != null;
        Long warehouse;
        Long source;
        if (ledger) {
            scope.requireLedger(action, c.ledger());
            var ids = c.ledger().scopeWarehouseIds();
            source = ids.isEmpty() ? null : ids.getFirst();
            warehouse = ids.isEmpty() ? null : ids.getLast();
        } else {
            warehouse =
                    sources.isEmpty()
                            ? c.logistics().targetWarehouseId()
                            : sources.getFirst().targetWarehouseId();
            source =
                    sources.isEmpty()
                            ? c.logistics().sourceWarehouseId()
                            : sources.getFirst().warehouseId();
            if (source == null && "DELIVERY".equals(c.logistics().legCode()))
                source = c.logistics().sourceWarehouseId();
            if (warehouse == null) warehouse = source;
            if (source == null) source = warehouse;
            scope.requireManual(action, "INVENTORY", c.cityCode());
            if (!sources.isEmpty() && (warehouse == null || source == null)) throw bad("来源缺少有效仓库");
            if (warehouse != null && source != null) scope.requirePair(source, warehouse, action);
        }
        c = withSavedLinkTimes(c, old);
        String body = body(c);
        String sourceBody = json.writeValueAsString(ledger ? c.ledger().sources() : sources);
        var values =
                new UpdateWrapper<OrderLogisticsCostEntity>()
                        .set("source_system", platformSource ? "HUOLALA" : sourceSystem(c, sources))
                        .set("title", c.title())
                        .set(
                                "type_code",
                                ledger
                                        ? c.ledger().transportPurposeCode()
                                        : c.logistics().legCode())
                        .set("city_code", c.cityCode())
                        .set("department_id", c.departmentId())
                        .set("owner_employee_code", c.ownerEmployeeCode())
                        .set("warehouse_id", warehouse)
                        .set("source_warehouse_id", source)
                        .set("cost_date", c.costDate())
                        .set("amount", c.amount())
                        .set("payment_status_code", c.paymentStatusCode())
                        .set(
                                "payer_code",
                                ledger
                                        ? ledgerPayer(c)
                                        : c.logistics() == null ? null : c.logistics().payerCode())
                        .set("data_json", body)
                        .set("sources_json", sourceBody)
                        .set(
                                ledger,
                                "transport_purpose_code",
                                ledger ? c.ledger().transportPurposeCode() : null)
                        .set(
                                ledger,
                                "delivery_mode_code",
                                ledger ? c.ledger().deliveryModeCode() : null)
                        .set(ledger, "carrier_code", ledger ? c.ledger().carrierCode() : null)
                        .set(ledger, "tracking_no", ledger ? c.ledger().trackingNo() : null)
                        .set(ledger, "cost_month", ledger ? c.ledger().costMonth() : null)
                        .set(
                                ledger && c.ledger().sourceSync() != null,
                                "origin_biz_key",
                                ledger && c.ledger().sourceSync() != null
                                        ? c.ledger().sourceSync().originBizKey()
                                        : null)
                        .set("warehouse_name", warehouseName)
                        .set("owner_employee_name", employeeName)
                        .set("updated_by", actor)
                        .set("updated_time", LocalDateTime.now(ZoneOffset.UTC));
        if (id == null) {
            if (previous != null) {
                if (!body(old.data()).equals(body)
                        || ledger
                                && !json.writeValueAsString(old.data().ledger().sources())
                                        .equals(sourceBody)) throw conflict("请求编号已用于另一笔费用");
                return old;
            }
            var row = new OrderLogisticsCostEntity();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenant);
            row.setDocumentNo(
                    new BusinessCodeGenerator().generate(BusinessCodeRule.millisecond("CST", 6)));
            row.setRequestId(c.requestId());
            row.setCategoryCode(category);
            row.setSourceSystem(sourceSystem(c, sources));
            row.setTitle(c.title());
            row.setTypeCode(ledger ? c.ledger().transportPurposeCode() : c.logistics().legCode());
            row.setCityCode(c.cityCode());
            row.setDepartmentId(c.departmentId());
            row.setOwnerEmployeeCode(c.ownerEmployeeCode());
            row.setWarehouseId(warehouse);
            row.setSourceWarehouseId(source);
            row.setCostDate(c.costDate());
            row.setAmount(c.amount());
            row.setPaymentStatusCode(c.paymentStatusCode());
            row.setPayerCode(
                    ledger
                            ? ledgerPayer(c)
                            : c.logistics() == null ? null : c.logistics().payerCode());
            row.setDataJson(body);
            row.setSourcesJson(sourceBody);
            row.setWarehouseName(warehouseName);
            row.setOwnerEmployeeName(employeeName);
            row.setCreatedBy(actor);
            row.setUpdatedBy(actor);
            mapper.insert(row);
            if (ledger) mapper.update(null, values.eq("tenant_id", tenant).eq("id", row.getId()));
            var saved = entry(tenant, category, row.getId(), action);
            audit(tenant, saved, "CREATE", actor, c.reason());
            return saved;
        }
        if (old.data().ledger() != null && !ledger) throw bad("新版费用不能通过旧版协议覆盖");
        var oldDocuments =
                old.data().logistics() == null ? null : old.data().logistics().externalDocuments();
        var newDocuments = c.logistics() == null ? null : c.logistics().externalDocuments();
        if (!ledger
                && oldDocuments != null
                && (c.logistics().sourceIds() != null && !c.logistics().sourceIds().isEmpty()
                        || oldDocuments.stream()
                                .anyMatch(document -> document.linkedId() != null))) {
            for (var original : oldDocuments)
                if (newDocuments == null
                        || newDocuments.stream()
                                .noneMatch(
                                        next ->
                                                java.util.Objects.equals(
                                                                original.sourceSystem(),
                                                                next.sourceSystem())
                                                        && java.util.Objects.equals(
                                                                original.documentNo(),
                                                                next.documentNo())))
                    throw bad("补关联必须保留每张单据的原始来源和原始单号");
        }
        transitionRule(old.statusCode(), "UPDATE", c.amount(), c.reason());
        if (mapper.update(
                        null,
                        values.eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("revision", c.revision())
                                .eq("deleted", 0)
                                .eq("status_code", "DRAFT")
                                .set("status_code", "DRAFT")
                                .setSql("revision=revision+1"))
                != 1) throw conflict("费用已被修改，请刷新后重试");
        if (!"order:logistics-cost:confirm".equals(action))
            audit(tenant, old, "BEFORE_UPDATE", actor, c.reason());
        var saved = entry(tenant, category, id, action);
        if (!"order:logistics-cost:confirm".equals(action))
            audit(tenant, saved, "UPDATE", actor, c.reason());
        return saved;
    }

    @Transactional
    public LogisticsCostDetailView transition(
            String tenant,
            String category,
            String id,
            LogisticsCostActionCommand c,
            String actor,
            String action) {
        lock(tenant);
        var old = entry(tenant, category, id, action);
        boolean confirm = action.endsWith(":confirm");
        if (confirm) {
            var sources = mapper.lockCarrierSource(tenant, id);
            if (sources != null)
                for (var source : sources) {
                    var carrier =
                            json.readValue(
                                    source.get("source_json").toString(),
                                    com.rigour.order.api.v1.model.LogisticsImportModels.CarrierOrder
                                            .class);
                    if (((Number) source.get("source_difference")).intValue() != 0)
                        throw conflict("货拉拉来源账单已变化，请先核对来源差异");
                    var verification =
                            source.get("bill_verification_json") == null
                                    ? null
                                    : json.readValue(
                                            source.get("bill_verification_json").toString(),
                                            com.rigour.order.api.v1.model.LogisticsImportModels
                                                    .BillVerification.class);
                    if (carrier.facts() == null && verification == null) continue;
                    var amount =
                            verification == null
                                    ? carrier.facts().billAmount()
                                    : verification.amount();
                    if (verification == null
                                    && !"FINAL".equals(carrier.facts().billState())
                                    && (c.reason() == null || c.reason().isBlank())
                            || amount == null
                            || old.data().amount() == null
                            || amount.compareTo(
                                            carrier.facts() != null
                                                            && verification == null
                                                            && old.data().ledger() != null
                                                            && old.data().ledger().feeItems()
                                                                    != null
                                                            && !old.data()
                                                                    .ledger()
                                                                    .feeItems()
                                                                    .isEmpty()
                                                    ? old.data().ledger().feeItems().stream()
                                                            .map(
                                                                    f ->
                                                                            f.feeAmount() == null
                                                                                    ? java.math
                                                                                            .BigDecimal
                                                                                            .ZERO
                                                                                    : f.feeAmount())
                                                            .reduce(
                                                                    java.math.BigDecimal.ZERO,
                                                                    java.math.BigDecimal::add)
                                                    : old.data().amount())
                                    != 0
                            || old.data().ledger() == null
                            || !carrier.orderNo().equals(old.data().ledger().platformOrderNo())
                            || !"HLL".equals(old.data().ledger().carrierCode()))
                        throw conflict("运输费用、货拉拉单号及已核对净账单不一致，请重新编辑后确认");
                }
        }
        if (confirm
                && old.data().ledger() == null
                && old.data().logistics().sourceIds() != null
                && !old.data().logistics().sourceIds().isEmpty()) {
            var transport = old.data().logistics();
            var current = sources(tenant, transport.legCode(), "", transport.sourceIds(), action);
            if (current.size() != old.sources().size()
                    || !new HashSet<>(current).equals(new HashSet<>(old.sources())))
                throw conflict("来源单据已变更或无权限，请重新编辑费用后确认");
        }
        transitionRule(
                old.statusCode(), confirm ? "CONFIRM" : "VOID", old.data().amount(), c.reason());
        if (mapper.update(
                        null,
                        new UpdateWrapper<OrderLogisticsCostEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("revision", c.revision())
                                .eq("status_code", old.statusCode())
                                .set("status_code", confirm ? "CONFIRMED" : "VOID")
                                .setSql("revision=revision+1")
                                .set("updated_by", actor)
                                .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)))
                != 1) throw conflict("费用已修改，请刷新");
        var saved = entry(tenant, category, id, action);
        audit(tenant, saved, confirm ? "CONFIRM" : "VOID", actor, c.reason());
        return saved;
    }

    @Transactional
    public LogisticsCostDetailView lifecycle(
            String tenant,
            String category,
            String id,
            LogisticsCostActionCommand command,
            String actor,
            String permission,
            boolean delete) {
        lock(tenant);
        var old = entry(tenant, category, id, permission);
        String operation = delete ? "DELETE" : "REVOKE";
        transitionRule(old.statusCode(), operation, old.data().amount(), command.reason());
        var update =
                new UpdateWrapper<OrderLogisticsCostEntity>()
                        .eq("tenant_id", tenant)
                        .eq("id", id)
                        .eq("deleted", 0)
                        .eq("status_code", old.statusCode())
                        .eq("revision", command.revision())
                        .set("updated_by", actor)
                        .set("updated_time", LocalDateTime.now(ZoneOffset.UTC))
                        .setSql("revision=revision+1");
        if (delete) update.set("deleted", 1);
        else update.set("status_code", "DRAFT");
        if (mapper.update(null, update) != 1) throw conflict("费用已变化，请刷新后重试");
        // 删除前已按完整数据范围授权；保留删除后的版本快照及附件供追溯。
        var saved =
                view(
                        mapper.selectOne(
                                new QueryWrapper<OrderLogisticsCostEntity>()
                                        .eq("tenant_id", tenant)
                                        .eq("category_code", category)
                                        .eq("id", id)));
        audit(tenant, saved, operation, actor, command.reason());
        return saved;
    }

    public void attachmentChanged(
            String tenant,
            String category,
            String id,
            String actor,
            String operation,
            String description,
            String permission) {
        audit(tenant, entry(tenant, category, id, permission), operation, actor, description);
    }

    public void attachmentAdded(
            String tenant,
            String category,
            String id,
            String actor,
            String attachmentId,
            String action) {
        audit(
                tenant,
                entry(tenant, category, id, action),
                "ATTACHMENT_ADDED",
                actor,
                "附件 " + attachmentId);
    }

    private void audit(
            String tenant, LogisticsCostDetailView v, String action, String actor, String reason) {
        var row = new OrderLogisticsCostAuditEntity();
        row.setTenantId(tenant);
        row.setEntryId(v.id());
        row.setActionCode(action);
        row.setActorId(actor);
        row.setReason(reason);
        row.setSnapshotJson(json.writeValueAsString(v));
        audits.insert(row);
    }

    public List<LogisticsCostAuditView> history(String tenant, String id) {
        return audits
                .selectList(
                        new QueryWrapper<OrderLogisticsCostAuditEntity>()
                                .eq("tenant_id", tenant)
                                .eq("entry_id", id)
                                .orderByDesc("id"))
                .stream()
                .map(
                        a -> {
                            boolean evidence =
                                    java.util.Set.of("SOURCE_ACCEPT", "BILL_VERIFY")
                                            .contains(a.getActionCode());
                            return new LogisticsCostAuditView(
                                    a.getActionCode(),
                                    a.getActorId(),
                                    a.getReason(),
                                    a.getCreatedTime().toInstant(ZoneOffset.UTC),
                                    evidence
                                            ? null
                                            : json.readValue(
                                                    a.getSnapshotJson(),
                                                    LogisticsCostDetailView.class),
                                    evidence
                                            ? json.readValue(
                                                    a.getSnapshotJson(),
                                                    new tools.jackson.core.type.TypeReference<
                                                            java.util.Map<String, Object>>() {})
                                            : null);
                        })
                .toList();
    }

    /** 关联时间由保存端产生；重试和普通编辑不刷新，历史缺失不猜测。 */
    static LogisticsCostCommand withSavedLinkTimes(
            LogisticsCostCommand command, LogisticsCostDetailView old) {
        if (command.ledger() == null) return command;
        var originals =
                old == null || old.data().ledger() == null
                        ? List.<LogisticsCostLedger.Source>of()
                        : old.data().ledger().sources();
        var now = java.time.Instant.now();
        var stamped =
                command.ledger().sources().stream()
                        .map(
                                source -> {
                                    var prior =
                                            originals.stream()
                                                    .filter(
                                                            candidate ->
                                                                    Objects.equals(
                                                                                    candidate
                                                                                            .orderTypeCode(),
                                                                                    source
                                                                                            .orderTypeCode())
                                                                            && Objects.equals(
                                                                                    candidate
                                                                                            .orderCode(),
                                                                                    source
                                                                                            .orderCode()))
                                                    .findFirst();
                                    return source.withBusinessSnapshot(
                                            source.documentAmount(),
                                            source.documentDate(),
                                            prior.isPresent() ? prior.get().linkedAt() : now);
                                })
                        .toList();
        return command.withLedger(command.ledger().withSources(stamped), command.amount());
    }

    private String body(LogisticsCostCommand c) {
        if (c.ledger() == null) return json.writeValueAsString(c);
        var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(c);
        var plan = (tools.jackson.databind.node.ObjectNode) tree.get("ledger");
        plan.remove("sources");
        if (plan.get("sender") instanceof tools.jackson.databind.node.ObjectNode sender)
            sender.remove("displayName");
        for (var point : plan.path("receivingPoints"))
            if (point instanceof tools.jackson.databind.node.ObjectNode row)
                row.remove("displayName");
        return json.writeValueAsString(tree);
    }

    private LogisticsCostDetailView view(OrderLogisticsCostEntity r) {
        var data = json.readValue(r.getDataJson(), LogisticsCostCommand.class);
        boolean ledger = data.ledger() != null;
        if (ledger)
            data =
                    data.withLedger(
                            data.ledger()
                                    .withSources(
                                            Arrays.asList(
                                                    json.readValue(
                                                            r.getSourcesJson(),
                                                            com.rigour.order.api.v1.model
                                                                                    .LogisticsCostLedger
                                                                                    .Source
                                                                            []
                                                                    .class))),
                            data.amount());
        return new LogisticsCostDetailView(
                r.getId(),
                r.getDocumentNo(),
                r.getCategoryCode(),
                r.getStatusCode(),
                r.getRevision(),
                data,
                ledger
                        ? List.of()
                        : Arrays.asList(
                                json.readValue(
                                        r.getSourcesJson(), LogisticsCostSourceView[].class)),
                r.getWarehouseName(),
                r.getOwnerEmployeeName(),
                r.getCreatedTime().toInstant(ZoneOffset.UTC),
                r.getUpdatedTime().toInstant(ZoneOffset.UTC),
                r.getCreatedBy(),
                r.getUpdatedBy());
    }

    public List<LogisticsCostSourceView> sources(
            String tenant, String leg, String keyword, List<Long> ids, String action) {
        return references.sources(leg, keyword, ids, action);
    }

    static void bind(QueryWrapper<?> q, String sql, List<Object> args) {
        var result = new StringBuilder();
        int index = 0;
        for (char c : sql.toCharArray())
            if (c == '?') result.append('{').append(index++).append('}');
            else result.append(c);
        q.apply(result.toString(), args.toArray());
    }

    private static String ledgerPayer(LogisticsCostCommand c) {
        if (c.ledger().feeItems().stream()
                .anyMatch(f -> "COMPANY".equals(f.bearerCode()) || "SHARED".equals(f.bearerCode())))
            return "COMPANY";
        return c.ledger().feeItems().stream().map(f -> f.bearerCode()).distinct().count() == 1
                ? c.ledger().feeItems().getFirst().bearerCode()
                : "OTHER";
    }

    private static String sourceSystem(
            LogisticsCostCommand c, List<LogisticsCostSourceView> sources) {
        if (c.ledger() != null)
            return c.ledger().sources().stream().allMatch(s -> "NONE".equals(s.orderTypeCode()))
                    ? "NONE"
                    : "SYSTEM";
        java.util.Set<String> values = new java.util.HashSet<>();
        if (c.logistics().externalDocuments() != null)
            c.logistics().externalDocuments().forEach(d -> values.add(d.sourceSystem()));
        for (var source : sources) {
            if (c.logistics().externalDocuments() != null
                    && c.logistics().externalDocuments().stream()
                            .anyMatch(d -> java.util.Objects.equals(d.linkedId(), source.id())))
                continue;
            String origin = source.sourceSystem();
            values.add(
                    origin == null || origin.isBlank() || "SYSTEM".equals(origin)
                            ? "SYSTEM"
                            : java.util.Set.of("DHB", "DINGHUOBAO").contains(origin)
                                    ? "DHB"
                                    : "OTHER");
        }
        if (values.isEmpty()) return "NONE";
        return values.size() == 1 ? values.iterator().next() : "MIXED";
    }

    private static boolean text(String v) {
        return v != null && !v.isBlank();
    }

    private static void transitionRule(
            String state, String action, BigDecimal amount, String reason) {
        try {
            LogisticsCostRules.transition(state, action, amount, reason);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    public List<LogisticsCostSourceView> sources(
            String tenant,
            String leg,
            String keyword,
            List<Long> ids,
            String action,
            String city,
            int begin) {
        return references.sources(leg, keyword, ids, action, city, begin);
    }
}
