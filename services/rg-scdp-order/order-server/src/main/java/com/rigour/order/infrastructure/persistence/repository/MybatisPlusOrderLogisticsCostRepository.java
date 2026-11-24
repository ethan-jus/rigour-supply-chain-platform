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
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Repository
public class MybatisPlusOrderLogisticsCostRepository implements OrderLogisticsCostStore {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rigour.order.application.port.out.PlatformTransportStore platformRecords;

    private static final Set<String> EVIDENCE_ACTIONS = Set.of("SOURCE_ACCEPT", "BILL_VERIFY", "SOURCE_SYNC");
    private static final TypeReference<Map<String, Object>> EVIDENCE_TYPE = new TypeReference<>() {};

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

    private static String missingDepartmentSql() {
        // MySQL 8.4 会将相关 EXISTS + JSON_TABLE 错误优化为空结果；标量计数保留逐记录关联。
        return "(SELECT COUNT(*) FROM"
                   + " JSON_TABLE(COALESCE(order_logistics_cost.sources_json,'[]'),'$[*]'"
                   + " COLUMNS(employee_code VARCHAR(64) PATH '$.employeeCode',department_id BIGINT"
                   + " PATH '$.departmentId',department_name VARCHAR(128) PATH '$.departmentName'))"
                   + " d WHERE NULLIF(d.employee_code,'') IS NOT NULL AND (d.department_id IS NULL"
                   + " OR NULLIF(d.department_name,'') IS NULL))>0";
    }

    @Override
    public List<String> departmentTenants() {
        return mapper
                .selectMaps(
                        new QueryWrapper<OrderLogisticsCostEntity>()
                                .eq("category_code", "LOGISTICS")
                                .eq("status_code", "ACTIVE")
                                .eq("deleted", 0)
                                .apply(missingDepartmentSql())
                                .select("DISTINCT tenant_id"))
                .stream()
                .map(row -> row.get("tenant_id").toString())
                .toList();
    }

    @Override
    public List<DepartmentCandidate> missingDepartments(String tenant, String afterId) {
        return mapper
                .selectList(
                        new QueryWrapper<OrderLogisticsCostEntity>()
                                .eq("tenant_id", tenant)
                                .eq("category_code", "LOGISTICS")
                                .eq("status_code", "ACTIVE")
                                .eq("deleted", 0)
                                .gt("id", afterId)
                                .apply(missingDepartmentSql())
                                .orderByAsc("id")
                                .last("LIMIT 100"))
                .stream()
                .map(
                        row ->
                                new DepartmentCandidate(
                                        row.getId(),
                                        row.getRevision(),
                                        departmentData(row)))
                .filter(row -> row.data().ledger() != null)
                .toList();
    }

    @Override
    @Transactional
    public boolean fillDepartments(
            String tenant,
            String id,
            int revision,
            java.util.Map<
                            OrderLogisticsReferences.DepartmentAt,
                            com.rigour.order.api.v1.model.LogisticsCostEmployeeView>
                    employees) {
        // 与其他成本写操作使用同一把租户锁；HR 请求已在事务外批量完成。
        mapper.ensureGuard(tenant);
        mapper.lock(tenant);
        var row =
                mapper.selectOne(
                        new QueryWrapper<OrderLogisticsCostEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("category_code", "LOGISTICS")
                                .eq("status_code", "ACTIVE")
                                .eq("deleted", 0)
                                .eq("revision", revision));
        if (row == null) return false;
        var prior = departmentData(row);
        if (prior.ledger() == null) return false;
        var ledger =
                com.rigour.order.application.service.cost.LogisticsDepartmentRefreshService.fill(
                        prior.ledger(), prior.costDate(), employees);
        if (ledger.equals(prior.ledger())) return false;
        var data = prior.withLedger(ledger, prior.amount());
        var actor = "SYSTEM";
        int changed =
                mapper.update(
                        null,
                        new UpdateWrapper<OrderLogisticsCostEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("revision", revision)
                                .eq("status_code", "ACTIVE")
                                .eq("deleted", 0)
                                .set("data_json", json.writeValueAsString(data))
                                .set("sources_json", json.writeValueAsString(ledger.sources()))
                                .set("updated_by", actor)
                                .set("updated_time", LocalDateTime.now(ZoneOffset.UTC))
                                .setSql("revision=revision+1"));
        if (changed == 0) return false;
        mapper.advanceAnalyticsVersion(tenant);
        row.setDataJson(json.writeValueAsString(data));
        row.setSourcesJson(json.writeValueAsString(ledger.sources()));
        row.setRevision(revision + 1);
        audit(tenant, view(row), "UPDATE", actor, "按已关联员工补齐缺失部门");
        return true;
    }

    private LogisticsCostCommand departmentData(OrderLogisticsCostEntity row) {
        var data = json.readValue(row.getDataJson(), LogisticsCostCommand.class);
        return data.ledger() == null ? data : data.withLedger(data.ledger().withSources(Arrays.asList(
                json.readValue(row.getSourcesJson(), LogisticsCostLedger.Source[].class))), data.amount());
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
                        + ".partyCode','"
                        + ledgerPath
                        + ".displayName','"
                        + ledgerPath
                        + ".address') IS NOT NULL OR"
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
        String shipmentSourceExists =
                "EXISTS(SELECT 1 FROM order_transport_shipment_source s WHERE"
                        + " s.tenant_id=order_logistics_cost.tenant_id AND"
                        + " s.cost_id=order_logistics_cost.id)";
        if ("MANUAL_ENTRY".equals(filter.sourceSystem()))
            q.ne("source_system", "HUOLALA").apply("NOT " + shipmentSourceExists);
        else if ("DHB".equals(filter.sourceSystem())) q.apply(shipmentSourceExists);
        else q.eq(text(filter.sourceSystem()), "source_system", filter.sourceSystem());
        if ("LINKED".equals(filter.relationState()) || "UNLINKED".equals(filter.relationState())) {
            String condition =
                    "(EXISTS(SELECT 1 FROM order_logistics_import_order i JOIN"
                        + " order_logistics_transport_link l ON l.tenant_id=i.tenant_id AND"
                        + " l.source_id=i.id WHERE i.tenant_id=order_logistics_cost.tenant_id AND"
                        + " i.cost_id=order_logistics_cost.id) OR JSON_LENGTH(sources_json)>0)";
            q.apply(("UNLINKED".equals(filter.relationState()) ? "NOT " : "") + condition);
        }
        String platformSource =
                " FROM order_logistics_import_order i WHERE i.tenant_id=order_logistics_cost.tenant_id"
                        + " AND i.cost_id=order_logistics_cost.id AND i.source_channel='API'";
        String sourceExists = "EXISTS(SELECT 1" + platformSource + " AND ";
        if (text(filter.deliveryModeCode()))
            q.apply("(CASE WHEN EXISTS(SELECT 1" + platformSource + ") THEN 'THIRD_PARTY'"
                    + " ELSE delivery_mode_code END)={0}", filter.deliveryModeCode().strip());
        if (text(filter.orderPlacerEmployeeCode()) || text(filter.orderPlacerDepartment())) {
            String sourceJson = "COALESCE(i.latest_source_json,i.source_json)";
            String platformEmployee = jsonText(sourceJson, "$.facts.mappedEmployeeCode");
            String platformDepartment = jsonText(sourceJson, "$.facts.mappedDepartmentName");
            String salesSources =
                    "CASE WHEN COALESCE(JSON_LENGTH(JSON_EXTRACT(data_json,'$.ledger.sources')),0)>0"
                            + " THEN JSON_EXTRACT(data_json,'$.ledger.sources') ELSE sources_json END";
            q.apply(
                    "(" + sourceExists
                            + "({0}='' OR " + platformEmployee + "={0})"
                            + " AND ({1}='' OR " + platformDepartment + "={1}))"
                            + " OR ((NOT EXISTS(SELECT 1" + platformSource + ") OR "
                            + shipmentSourceExists + ")"
                            + " AND (SELECT COUNT(*) FROM JSON_TABLE(COALESCE(" + salesSources + ",JSON_ARRAY()),"
                            + " '$[*]' COLUMNS(order_type VARCHAR(32) PATH '$.orderTypeCode',"
                            + " source_type VARCHAR(32) PATH '$.sourceType',"
                            + " employee_code VARCHAR(50) PATH '$.employeeCode',"
                            + " department VARCHAR(128) PATH '$.departmentName')) business"
                            + " WHERE COALESCE(business.order_type,business.source_type)"
                            + " IN ('SALES_ORDER','DELIVERY')"
                            + " AND ({0}='' OR business.employee_code={0})"
                            + " AND ({1}='' OR TRIM(business.department)={1}))>0))",
                    Objects.toString(filter.orderPlacerEmployeeCode(), "").strip(),
                    java.util.Objects.toString(filter.orderPlacerDepartment(), "").strip());
        }
        if (text(filter.businessOrderNo())) {
            String pattern = filter.businessOrderNo().strip()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            q.apply(
                    "JSON_SEARCH(COALESCE((SELECT JSON_EXTRACT(i.business_json,'$.orderCodes')"
                            + platformSource + " LIMIT 1),"
                            + " CASE WHEN COALESCE(JSON_LENGTH(JSON_EXTRACT(data_json,'$.ledger.sources')),0)>0"
                            + " THEN JSON_EXTRACT(data_json,'$.ledger.sources[*].orderCode')"
                            + " ELSE JSON_EXTRACT(sources_json,'$[*].documentNo') END),"
                            + " 'one',{0}) IS NOT NULL",
                    "%" + pattern + "%");
        }
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
        if (text(filter.receiverName())) {
            // 未关联平台订单还没有本系统收货点，必须同时查询平台原始路线；不把发货地当收货地。
            q.and(
                    w ->
                            w.nested(
                                            local ->
                                                    partyFilter(
                                                            local,
                                                            "receiverName",
                                                            filter.receiverName()))
                                    .or()
                                    .apply(
                                            "EXISTS(SELECT 1 FROM order_logistics_import_order i"
                                                + " JOIN"
                                                + " JSON_TABLE(COALESCE(i.latest_source_json,i.source_json),'$.facts.stops[*]'"
                                                + " COLUMNS(pos FOR ORDINALITY,stop_name"
                                                + " VARCHAR(500) PATH '$.name', stop_address"
                                                + " VARCHAR(1000) PATH '$.address')) p WHERE"
                                                + " i.tenant_id=order_logistics_cost.tenant_id AND"
                                                + " i.cost_id=order_logistics_cost.id AND p.pos>1"
                                                + " AND (LOCATE({0},p.stop_name)>0 OR"
                                                + " LOCATE({0},p.stop_address)>0))",
                                            filter.receiverName().trim()));
        }
        if (Boolean.TRUE.equals(filter.salesSelectable())) {
            q.eq("status_code", "ACTIVE")
                    .eq("carrier_code", "HLL")
                    .eq("source_system", "HUOLALA")
                    .and(w -> w.isNull("type_code").or().eq("type_code", "DELIVERY"))
                    .apply(
                            "EXISTS(SELECT 1 FROM order_logistics_import_order i WHERE"
                                + " i.tenant_id=order_logistics_cost.tenant_id AND"
                                + " i.cost_id=order_logistics_cost.id AND i.source_channel='API'"
                                + " AND JSON_UNQUOTE(JSON_EXTRACT("
                                + " COALESCE(i.latest_source_json,i.source_json),'$.status'))='已完成')");
            if (filter.dateFrom() != null)
                q.apply(transportTimeSql() + ">={0}", filter.dateFrom().atStartOfDay());
            if (filter.dateTo() != null)
                q.apply(transportTimeSql() + "<{0}", filter.dateTo().plusDays(1).atStartOfDay());
        }
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
        if (!text(filter.statusCode())) q.eq("status_code", "ACTIVE");
        if ("COMPLETE".equals(filter.statusCode()) || "INCOMPLETE".equals(filter.statusCode()))
            q.eq("status_code", "ACTIVE")
                    .apply(
                            ("INCOMPLETE".equals(filter.statusCode()) ? "NOT " : "")
                                    + completeBusinessSql());
        q.in("OTHER".equals(filter.typeCode()), "type_code", List.of("OTHER", "NON_SALES"))
                .eq(
                        text(filter.typeCode()) && !"OTHER".equals(filter.typeCode()),
                        "type_code",
                        filter.typeCode())
                .eq(text(filter.cityCode()), "city_code", filter.cityCode())
                .eq(
                        text(filter.statusCode())
                                && !List.of("COMPLETE", "INCOMPLETE").contains(filter.statusCode()),
                        "status_code",
                        filter.statusCode())
                .eq(
                        text(filter.paymentStatusCode()),
                        "payment_status_code",
                        filter.paymentStatusCode())
                .eq(filter.warehouseId() != null, "warehouse_id", filter.warehouseId())
                .eq(
                        text(filter.ownerEmployeeCode()),
                        "owner_employee_code",
                        filter.ownerEmployeeCode())
                .ge(
                        !Boolean.TRUE.equals(filter.salesSelectable()) && filter.dateFrom() != null,
                        "cost_date",
                        filter.dateFrom())
                .le(
                        !Boolean.TRUE.equals(filter.salesSelectable()) && filter.dateTo() != null,
                        "cost_date",
                        filter.dateTo());
        return q;
    }

    private static String jsonText(String json, String path) {
        return "NULLIF(NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(" + json + ",'" + path
                + "'))),''),'null')";
    }

    private static final String BUSINESS_LINKS =
            "JSON_TABLE(COALESCE(sources_json,'[]'),'$[*]' COLUMNS(order_code VARCHAR(100) PATH"
                    + " '$.orderCode', document_no VARCHAR(100) PATH '$.documentNo',role_code"
                    + " VARCHAR(20) PATH '$.linkRoleCode', employee_code VARCHAR(64) PATH"
                    + " '$.employeeCode',department_id VARCHAR(64) PATH '$.departmentId')) links"
                    + " WHERE (links.role_code IS NULL OR links.role_code='COST')";
    private static final List<ReadinessCheck> READINESS =
            List.of(
                    new ReadinessCheck("AMOUNT", "amount IS NOT NULL"),
                    new ReadinessCheck(
                            "ORDER",
                            "EXISTS(SELECT 1 FROM "
                                    + BUSINESS_LINKS
                                    + " AND COALESCE(NULLIF(links.order_code,''),NULLIF(links.document_no,''))"
                                    + " IS NOT NULL)"),
                    new ReadinessCheck(
                            "EMPLOYEE",
                            "NOT EXISTS(SELECT 1 FROM "
                                    + BUSINESS_LINKS
                                    + " AND NULLIF(links.employee_code,'') IS NULL)"),
                    new ReadinessCheck(
                            "DEPARTMENT",
                            "NOT EXISTS(SELECT 1 FROM "
                                    + BUSINESS_LINKS
                                    + " AND NULLIF(links.department_id,'') IS NULL)"),
                    new ReadinessCheck(
                            "EVIDENCE",
                            "(EXISTS(SELECT 1 FROM order_logistics_import_order i WHERE"
                                + " i.tenant_id=order_logistics_cost.tenant_id AND"
                                + " i.cost_id=order_logistics_cost.id AND i.source_channel='API')"
                                + " OR EXISTS(SELECT 1 FROM order_transport_shipment_source s WHERE"
                                + " s.tenant_id=order_logistics_cost.tenant_id AND"
                                + " s.cost_id=order_logistics_cost.id) OR EXISTS(SELECT 1 FROM"
                                + " order_logistics_cost_attachment a WHERE"
                                + " a.tenant_id=order_logistics_cost.tenant_id AND"
                                + " a.entry_id=order_logistics_cost.id AND a.deleted=0))"));

    private record ReadinessCheck(String code, String condition) {}

    /** 列表状态与 BI 缺项使用同一校验条件。 */
    static String completeBusinessSql() {
        return READINESS.stream()
                .map(ReadinessCheck::condition)
                .collect(java.util.stream.Collectors.joining(" AND ", "(", ")"));
    }

    static String missingBusinessSql() {
        return READINESS.stream()
                .map(
                        check ->
                                "CASE WHEN "
                                        + check.condition()
                                        + " THEN NULL ELSE '"
                                        + check.code()
                                        + "' END")
                .collect(java.util.stream.Collectors.joining(",", "CONCAT_WS(',',", ")"));
    }

    @Override
    public java.util.Map<String, String> businessStatuses(
            String tenant, List<String> ids, String action) {
        if (ids.isEmpty()) return java.util.Map.of();
        var result = new java.util.HashMap<String, String>();
        for (var r :
                mapper.selectMaps(
                        where(tenant, "LOGISTICS", action)
                                .in("id", ids)
                                .select(
                                        "id",
                                        "CASE WHEN status_code='VOID' THEN 'VOID' WHEN "
                                                + completeBusinessSql()
                                                + " THEN 'COMPLETE' ELSE 'INCOMPLETE' END"
                                                + " business_status")))
            result.put(r.get("id").toString(), r.get("business_status").toString());
        return result;
    }

    @Override
    public List<LogisticsCostDetailView> trackingCandidates(
            String tenant, List<String> numbers, String carrierCode, String action) {
        if (numbers.isEmpty()) return List.of();
        if (numbers.size() > 500) throw bad("一次最多匹配500个快递单号");
        String encoded = json.writeValueAsString(numbers);
        var q = where(tenant, "LOGISTICS", action).eq("status_code", "ACTIVE");
        if (text(carrierCode)) q.eq("carrier_code", carrierCode);
        q.and(
                w ->
                        w.in("tracking_no", numbers)
                                .or()
                                .in(
                                        "JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.ledger.platformOrderNo'))",
                                        numbers)
                                .or()
                                .apply(
                                        "EXISTS(SELECT 1 FROM order_logistics_import_order i WHERE"
                                            + " i.tenant_id=order_logistics_cost.tenant_id AND"
                                            + " i.cost_id=order_logistics_cost.id AND"
                                            + " JSON_CONTAINS({0},JSON_QUOTE(i.carrier_order_no)))",
                                        encoded)
                                .or()
                                .apply(
                                        "EXISTS(SELECT 1 FROM order_transport_shipment_source s"
                                            + " WHERE s.tenant_id=order_logistics_cost.tenant_id"
                                            + " AND s.cost_id=order_logistics_cost.id AND"
                                            + " JSON_OVERLAPS(JSON_EXTRACT(s.latest_json,'$.trackingNumbers'),{0}))",
                                        encoded));
        var rows = mapper.selectList(q.last("LIMIT 2001"));
        if (rows.size() > 2000) throw bad("匹配结果过多，请按物流公司或拆分文件后导入");
        return rows.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public LogisticsCostPageView entries(
            String tenant, String category, LogisticsCostQuery filter, String action) {
        var totals =
                mapper.selectMaps(
                                query(tenant, category, filter, action)
                                        .select(
                                                "COUNT(*) total",
                                                "COALESCE(SUM(CASE WHEN status_code='ACTIVE'"
                                                        + " THEN amount ELSE 0"
                                                        + " END),0) confirmed",
                                                "COALESCE(SUM(CASE WHEN status_code='ACTIVE' AND"
                                                    + " payer_code='COMPANY' AND"
                                                    + " payment_status_code='PAID' THEN amount ELSE"
                                                    + " 0 END),0) paid",
                                                "COALESCE(SUM(status_code='ACTIVE' AND amount IS"
                                                        + " NULL),0) pending",
                                                "COALESCE(SUM(CASE WHEN status_code<>'VOID' THEN"
                                                        + " amount ELSE 0 END),0) registered",
                                                "COALESCE(SUM(status_code<>'VOID' AND amount IS"
                                                        + " NULL),0) missing",
                                                "COALESCE(SUM(CASE WHEN status_code='ACTIVE' AND"
                                                    + " amount IS NULL THEN amount ELSE 0 END),0)"
                                                    + " pending_amount",
                                                "COALESCE(SUM(status_code='ACTIVE' AND amount=0"
                                                        + " AND payer_code IN"
                                                        + " ('SUPPLIER','CUSTOMER','OTHER')),0)"
                                                        + " third_party"))
                        .getFirst();
        var rows =
                mapper
                        .selectList(
                                query(tenant, category, filter, action)
                                        // 平台下单时间／订货宝实际发货时间／手工发货日期，统一在分页前排序。
                                        .orderByDesc(transportTimeSql())
                                        .orderByDesc("created_time", "id")
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

    static String transportTimeSql() {
        String placed =
                "TRIM(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(i.latest_source_json,i.source_json),'$.raw.\"下单时间\"')))";
        String shipped = "JSON_UNQUOTE(JSON_EXTRACT(s.latest_json,'$.shipTime'))";
        // 先明确判断长度再转换；真实 MySQL 对照中，CAST 内嵌套 NULLIF 会误将有效时间变为 NULL。
        // 归并时来源 ID 与费用 ID 不同；按费用关联读取 API 来源，与列表展示口径一致。
        return "COALESCE((SELECT "
                + sqlDateTime(placed)
                + " FROM order_logistics_import_order i WHERE i.cost_id=order_logistics_cost.id AND"
                + " i.source_channel='API' AND"
                + " i.tenant_id=order_logistics_cost.tenant_id LIMIT 1),(SELECT"
                + " MIN(TIMESTAMPADD(HOUR,8,"
                + sqlDateTime(shipped)
                + ")) FROM order_transport_shipment_source s WHERE"
                + " s.tenant_id=order_logistics_cost.tenant_id AND"
                + " s.cost_id=order_logistics_cost.id),CASE WHEN source_system='HUOLALA' THEN NULL"
                + " ELSE COALESCE("
                + sqlDateTime("JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.ledger.shippingTime'))")
                + ",CAST(cost_date AS DATETIME)) END)";
    }

    private static String sqlDateTime(String value) {
        return "CASE WHEN CHAR_LENGTH("
                + value
                + ")>=19 THEN CAST(REPLACE(SUBSTRING("
                + value
                + ",1,19),'T',' ') AS DATETIME) ELSE NULL END";
    }

    public LogisticsCostDetailView entry(String tenant, String category, String id, String action) {
        var row = mapper.selectOne(where(tenant, category, action).eq("id", id));
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "费用不存在或无访问权限", List.of());
        return view(row);
    }

    public List<LogisticsCostDetailView> linkedSales(String tenant, String orderNo, String action) {
        return mapper
                .selectList(
                        where(tenant, "LOGISTICS", action)
                                .apply(
                                        "JSON_SEARCH(sources_json,'one',{0},NULL,'$[*].orderCode','$[*].documentNo')"
                                            + " IS NOT NULL",
                                        orderNo)
                                .orderByDesc("created_time"))
                .stream()
                .map(this::view)
                .filter(
                        r ->
                                r.sources().stream()
                                                .anyMatch(
                                                        s ->
                                                                orderNo.equals(s.documentNo())
                                                                        && "SALES_ORDER"
                                                                                .equals(
                                                                                        s
                                                                                                .sourceType()))
                                        || r.data().ledger() != null
                                                && r.data().ledger().sources().stream()
                                                        .anyMatch(
                                                                s ->
                                                                        orderNo.equals(
                                                                                        s
                                                                                                .orderCode())
                                                                                && "SALES_ORDER"
                                                                                        .equals(
                                                                                                s
                                                                                                        .orderTypeCode())))
                .toList();
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
        // 人工更改金额或采用自定义分摊后，来源同步不得重算该成本。
        if (old == null
                || !sameAmount(old.data().amount(), c.amount())
                || c.ledger() != null
                        && c.ledger().feeItems().stream()
                                .anyMatch(f -> "MANUAL".equals(f.allocationMethodCode())))
            values.set("amount_manually_edited", true);
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
            row.setDocumentNo(CostDocumentNumber.next());
            row.setRequestId(c.requestId());
            row.setCategoryCode(category);
            row.setSourceSystem(sourceSystem(c, sources));
            row.setStatusCode("ACTIVE");
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
            mapper.update(null, values.eq("tenant_id", tenant).eq("id", row.getId()));
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
                                .eq("status_code", "ACTIVE")
                                .set("status_code", "ACTIVE")
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
        if (action.endsWith(":confirm")) throw bad("运输记录保存即生效，无需确认");
        transitionRule(old.statusCode(), "VOID", old.data().amount(), c.reason());
        if (mapper.update(
                        null,
                        new UpdateWrapper<OrderLogisticsCostEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("revision", c.revision())
                                .eq("status_code", old.statusCode())
                                .set("status_code", "VOID")
                                .setSql("revision=revision+1")
                                .set("updated_by", actor)
                                .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)))
                != 1) throw conflict("费用已修改，请刷新");
        var saved = entry(tenant, category, id, action);
        audit(tenant, saved, "VOID", actor, c.reason());
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
        if (!delete) throw bad("运输记录保存即生效，无需撤销确认");
        String operation = "DELETE";
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
        else throw bad("运输记录不支持撤销确认");
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
                .map(this::auditView)
                .toList();
    }

    private LogisticsCostAuditView auditView(OrderLogisticsCostAuditEntity row) {
        var payload = json.readTree(row.getSnapshotJson());
        String action = row.getActionCode();
        var occurredTime = row.getCreatedTime().toInstant(ZoneOffset.UTC);
        // 早期发货编辑把发货快照存为 UPDATE；常规费用 UPDATE 的根节点是费用详情。
        boolean legacyShipmentEdit = "UPDATE".equals(action)
                && payload.hasNonNull("shipmentNo") && payload.hasNonNull("connectorId");
        if (legacyShipmentEdit || LogisticsCostAuditView.SHIPMENT_LOGISTICS_EDIT.equals(action)) {
            return new LogisticsCostAuditView(LogisticsCostAuditView.SHIPMENT_LOGISTICS_EDIT,
                    row.getActorId(), row.getReason(), occurredTime, null, shipmentEvidence(payload));
        }
        boolean evidence = EVIDENCE_ACTIONS.contains(action);
        return new LogisticsCostAuditView(action, row.getActorId(), row.getReason(), occurredTime,
                evidence ? null : json.treeToValue(payload, LogisticsCostDetailView.class),
                evidence ? json.convertValue(payload, EVIDENCE_TYPE) : null);
    }

    private Map<String, Object> shipmentEvidence(JsonNode payload) {
        var evidence = json.createObjectNode();
        for (String field : List.of("shipmentNo", "carrierCode", "carrierName", "trackingNumbers", "shipTime")) {
            if (payload.has(field)) evidence.set(field, payload.get(field));
        }
        var remark = payload.path("shipmentRemark");
        if (remark.isMissingNode()) remark = payload.path("sourceFields").path("shipmentRemark");
        if (!remark.isMissingNode()) evidence.set("shipmentRemark", remark);
        return json.convertValue(evidence, EVIDENCE_TYPE);
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
        // 保存入口已按租户及操作权限解析收发方，保留服务端核实的名称快照。
        // 删除快照会让每次详情、附件校验和保存回执再次逐点请求 CRM。
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

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
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
