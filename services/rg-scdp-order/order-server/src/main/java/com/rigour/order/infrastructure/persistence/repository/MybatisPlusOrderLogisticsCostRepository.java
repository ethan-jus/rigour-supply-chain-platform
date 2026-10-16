package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.order.api.v1.model.LogisticsCostActionCommand;
import com.rigour.order.api.v1.model.LogisticsCostAuditView;
import com.rigour.order.api.v1.model.LogisticsCostCommand;
import com.rigour.order.api.v1.model.LogisticsCostDetailView;
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
import java.util.UUID;

@Repository
public class MybatisPlusOrderLogisticsCostRepository implements OrderLogisticsCostStore {
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
    }

    private QueryWrapper<OrderLogisticsCostEntity> where(
            String tenant, String category, String action) {
        var q =
                new QueryWrapper<OrderLogisticsCostEntity>()
                        .eq("tenant_id", tenant)
                        .eq("category_code", category);
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
        return q;
    }

    private QueryWrapper<OrderLogisticsCostEntity> query(
            String tenant, String category, LogisticsCostQuery filter, String action) {
        var q = where(tenant, category, action);
        q.eq(text(filter.sourceSystem()), "source_system", filter.sourceSystem());
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
                    "LOCATE({0},JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.trackingNo')))>0",
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
                        + " JSON_SEARCH(sources_json,'one',{0},NULL,'$[*].documentNo') IS NOT NULL"
                        + " OR JSON_SEARCH(data_json,'one',{0},NULL,'$.logistics.externalDocuments[*].documentNo')"
                        + " IS NOT NULL)",
                    "%" + value + "%");
        }
        q.eq(text(filter.typeCode()), "type_code", filter.typeCode())
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
                                                        + " NULL),0) missing"))
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
                ((Number) totals.get("missing")).longValue());
    }

    public LogisticsCostDetailView entry(String tenant, String category, String id, String action) {
        var row = mapper.selectOne(where(tenant, category, action).eq("id", id));
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "费用不存在或无访问权限", List.of());
        return view(row);
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
        Long warehouse =
                sources.isEmpty()
                        ? c.logistics().targetWarehouseId()
                        : sources.getFirst().targetWarehouseId();
        Long source =
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
        String body = json.writeValueAsString(c);
        var values =
                new UpdateWrapper<OrderLogisticsCostEntity>()
                        .set("source_system", sourceSystem(c, sources))
                        .set("title", c.title())
                        .set("type_code", c.logistics().legCode())
                        .set("city_code", c.cityCode())
                        .set("department_id", c.departmentId())
                        .set("owner_employee_code", c.ownerEmployeeCode())
                        .set("warehouse_id", warehouse)
                        .set("source_warehouse_id", source)
                        .set("cost_date", c.costDate())
                        .set("amount", c.amount())
                        .set("payment_status_code", c.paymentStatusCode())
                        .set("payer_code", c.logistics() == null ? null : c.logistics().payerCode())
                        .set("data_json", body)
                        .set("sources_json", json.writeValueAsString(sources))
                        .set("warehouse_name", warehouseName)
                        .set("owner_employee_name", employeeName)
                        .set("updated_by", actor)
                        .set("updated_time", LocalDateTime.now(ZoneOffset.UTC));
        if (id == null) {
            var previous =
                    mapper.selectOne(
                            new QueryWrapper<OrderLogisticsCostEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("request_id", c.requestId()));
            if (previous != null) {
                var old = entry(tenant, category, previous.getId(), action);
                if (!json.writeValueAsString(old.data()).equals(body))
                    throw conflict("请求编号已用于另一笔费用");
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
            row.setTypeCode(c.logistics().legCode());
            row.setCityCode(c.cityCode());
            row.setDepartmentId(c.departmentId());
            row.setOwnerEmployeeCode(c.ownerEmployeeCode());
            row.setWarehouseId(warehouse);
            row.setSourceWarehouseId(source);
            row.setCostDate(c.costDate());
            row.setAmount(c.amount());
            row.setPaymentStatusCode(c.paymentStatusCode());
            row.setPayerCode(c.logistics() == null ? null : c.logistics().payerCode());
            row.setDataJson(body);
            row.setSourcesJson(json.writeValueAsString(sources));
            row.setWarehouseName(warehouseName);
            row.setOwnerEmployeeName(employeeName);
            row.setCreatedBy(actor);
            row.setUpdatedBy(actor);
            mapper.insert(row);
            var saved = entry(tenant, category, row.getId(), action);
            audit(tenant, saved, "CREATE", actor, c.reason());
            return saved;
        }
        var old = entry(tenant, category, id, action);
        var oldDocuments = old.data().logistics().externalDocuments();
        var newDocuments = c.logistics().externalDocuments();
        if (oldDocuments != null
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
                                .ne("status_code", "VOID")
                                .set("status_code", "DRAFT")
                                .setSql("revision=revision+1"))
                != 1) throw conflict("费用已被修改，请刷新后重试");
        audit(tenant, old, "BEFORE_UPDATE", actor, c.reason());
        var saved = entry(tenant, category, id, action);
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
        if (confirm
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
                        a ->
                                new LogisticsCostAuditView(
                                        a.getActionCode(),
                                        a.getActorId(),
                                        a.getReason(),
                                        a.getCreatedTime().toInstant(ZoneOffset.UTC),
                                        json.readValue(
                                                a.getSnapshotJson(),
                                                LogisticsCostDetailView.class)))
                .toList();
    }

    private LogisticsCostDetailView view(OrderLogisticsCostEntity r) {
        return new LogisticsCostDetailView(
                r.getId(),
                r.getDocumentNo(),
                r.getCategoryCode(),
                r.getStatusCode(),
                r.getRevision(),
                json.readValue(r.getDataJson(), LogisticsCostCommand.class),
                Arrays.asList(json.readValue(r.getSourcesJson(), LogisticsCostSourceView[].class)),
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

    private static String sourceSystem(
            LogisticsCostCommand c, List<LogisticsCostSourceView> sources) {
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
