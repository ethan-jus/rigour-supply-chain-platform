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
        bind(q, p.sql(), p.args());
        return q;
    }

    private QueryWrapper<OrderLogisticsCostEntity> query(
            String tenant, String category, LogisticsCostQuery filter, String action) {
        var q = where(tenant, category, action);
        if (text(filter.keyword()))
            q.and(
                    w ->
                            w.apply(
                                            "JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.trackingNo'))"
                                                + " LIKE {0}",
                                            "%" + filter.keyword() + "%")
                                    .or()
                                    .like("document_no", filter.keyword())
                                    .or()
                                    .like("title", filter.keyword())
                                    .or()
                                    .apply(
                                            "JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.remark')) LIKE"
                                                    + " {0}",
                                            "%" + filter.keyword() + "%")
                                    .or()
                                    .apply(
                                            "CAST(sources_json AS CHAR) LIKE {0}",
                                            "%" + filter.keyword() + "%"));
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
                                                "COALESCE(SUM(CASE WHEN status_code='CONFIRMED' AND"
                                                        + " payer_code='COMPANY' THEN amount ELSE 0"
                                                        + " END),0) confirmed",
                                                "COALESCE(SUM(CASE WHEN status_code='CONFIRMED' AND"
                                                    + " payer_code='COMPANY' AND"
                                                    + " payment_status_code='PAID' THEN amount ELSE"
                                                    + " 0 END),0) paid",
                                                "COALESCE(SUM(status_code='DRAFT'),0) pending"))
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
                rows);
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
        Long warehouse = sources.isEmpty() ? null : sources.getFirst().targetWarehouseId();
        Long source = sources.isEmpty() ? warehouse : sources.getFirst().warehouseId();
        if (warehouse == null) warehouse = source;
        if (source == null) source = warehouse;
        if (warehouse == null || source == null) throw bad("来源缺少有效仓库");
        scope.requirePair(source, warehouse, action);
        String body = json.writeValueAsString(c);
        var values =
                new UpdateWrapper<OrderLogisticsCostEntity>()
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
        if (confirm) {
            var transport = old.data().logistics();
            var current = sources(tenant, transport.legCode(), "", transport.sourceIds(), action);
            if (current.size() != old.sources().size()
                    || !new HashSet<>(current).equals(new HashSet<>(old.sources())))
                throw conflict("来源单据已变更或无权限，请重新编辑费用后确认");
        }
        if (confirm) {
            var l = old.data().logistics();
            var current = sources(tenant, l.legCode(), "", l.sourceIds(), action);
            if (!new HashSet<>(current).equals(new HashSet<>(old.sources())))
                throw conflict("来源单据已变化或无权限，请重新编辑核对");
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
                r.getUpdatedTime().toInstant(ZoneOffset.UTC));
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
}
