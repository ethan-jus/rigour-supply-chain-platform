package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.erp.api.v1.model.ErpCostActionCommand;
import com.rigour.erp.api.v1.model.ErpCostAuditView;
import com.rigour.erp.api.v1.model.ErpCostCommand;
import com.rigour.erp.api.v1.model.ErpCostDetailView;
import com.rigour.erp.api.v1.model.ErpCostEmployeeView;
import com.rigour.erp.api.v1.model.ErpCostOptionView;
import com.rigour.erp.api.v1.model.ErpCostPageView;
import com.rigour.erp.api.v1.model.ErpCostQuery;
import com.rigour.erp.api.v1.model.ErpCostSourceView;
import com.rigour.erp.application.port.out.CostReferenceSource;
import com.rigour.erp.application.port.out.ErpCostStore;
import com.rigour.erp.domain.cost.CostRules;
import com.rigour.erp.infrastructure.persistence.entity.ErpCostAuditEntity;
import com.rigour.erp.infrastructure.persistence.entity.ErpCostEntity;
import com.rigour.erp.infrastructure.persistence.mapper.ErpCostAuditMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ErpCostMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ErpCostReferenceMapper;
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
import java.util.List;
import java.util.UUID;

@Repository
public class MybatisPlusErpCostRepository implements ErpCostStore {
    private final ErpCostMapper mapper;
    private final ErpCostAuditMapper audits;
    private final ObjectMapper json;
    private final ErpWarehouseDataScope scope;
    private final CostReferenceSource references;
    private final ErpCostReferenceMapper referenceMapper;

    public MybatisPlusErpCostRepository(
            ErpCostMapper mapper,
            ErpCostAuditMapper audits,
            ObjectMapper json,
            ErpWarehouseDataScope scope,
            CostReferenceSource references,
            ErpCostReferenceMapper referenceMapper) {
        this.mapper = mapper;
        this.audits = audits;
        this.json = json;
        this.scope = scope;
        this.references = references;
        this.referenceMapper = referenceMapper;
    }

    private void lock(String tenant) {
        mapper.ensureGuard(tenant);
        mapper.lock(tenant);
    }

    private QueryWrapper<ErpCostEntity> where(String tenant, String category, String action) {
        var q =
                new QueryWrapper<ErpCostEntity>()
                        .eq("tenant_id", tenant)
                        .eq("category_code", category);
        if ("MARKETING".equals(category)) {
            var people = references.employees(action);
            if (people.isEmpty()) q.apply("1=0");
            else
                q.in(
                        "owner_employee_code",
                        people.stream().map(ErpCostEmployeeView::employeeCode).toList());
        } else {
            var p = scope.predicate(action, "warehouse_id", "source_warehouse_id");
            bind(q, p.sql(), p.args());
        }
        return q;
    }

    private QueryWrapper<ErpCostEntity> query(
            String tenant, String category, ErpCostQuery filter, String action) {
        var q = where(tenant, category, action);
        if (text(filter.keyword()))
            q.and(
                    w ->
                            w.apply(
                                            "JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.rental.contractNo'))"
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
    public ErpCostPageView entries(
            String tenant, String category, ErpCostQuery filter, String action) {
        var totals =
                mapper.selectMaps(
                                query(tenant, category, filter, action)
                                        .select(
                                                "COUNT(*) total",
                                                "COALESCE(SUM(CASE WHEN status_code='CONFIRMED'"
                                                        + " THEN amount ELSE 0 END),0) confirmed",
                                                "COALESCE(SUM(CASE WHEN status_code='CONFIRMED' AND"
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
        return new ErpCostPageView(
                ((Number) totals.get("total")).longValue(),
                filter.begin(),
                filter.step(),
                new BigDecimal(totals.get("confirmed").toString()),
                new BigDecimal(totals.get("paid").toString()),
                ((Number) totals.get("pending")).longValue(),
                rows);
    }

    public ErpCostDetailView entry(String tenant, String category, String id, String action) {
        var row = mapper.selectOne(where(tenant, category, action).eq("id", id));
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "费用不存在或无访问权限", List.of());
        return view(row);
    }

    @Transactional
    public ErpCostDetailView save(
            String tenant,
            String category,
            String id,
            ErpCostCommand c,
            List<ErpCostSourceView> sources,
            String warehouseName,
            String employeeName,
            String actor,
            String action) {
        lock(tenant);
        Long warehouse =
                c.rental() != null
                        ? c.rental().warehouseId()
                        : sources.isEmpty() ? null : sources.getFirst().targetWarehouseId();
        Long source = sources.isEmpty() ? warehouse : sources.getFirst().warehouseId();
        if (warehouse == null) warehouse = source;
        if (source == null) source = warehouse;
        if (!"MARKETING".equals(category)) {
            if (warehouse == null || source == null) throw bad("来源缺少有效仓库");
            scope.requirePair(tenant, source, warehouse, action);
        }
        String body = json.writeValueAsString(c);
        var values =
                new UpdateWrapper<ErpCostEntity>()
                        .set("title", c.title())
                        .set("type_code", c.typeCode())
                        .set("city_code", c.cityCode())
                        .set("department_id", c.departmentId())
                        .set("owner_employee_code", c.ownerEmployeeCode())
                        .set("warehouse_id", warehouse)
                        .set("source_warehouse_id", source)
                        .set("cost_date", c.costDate())
                        .set("amount", c.amount())
                        .set("payment_status_code", c.paymentStatusCode())
                        .set("payer_code", null)
                        .set("data_json", body)
                        .set("sources_json", json.writeValueAsString(sources))
                        .set("warehouse_name", warehouseName)
                        .set("owner_employee_name", employeeName)
                        .set("updated_by", actor)
                        .set("updated_time", LocalDateTime.now(ZoneOffset.UTC));
        if (id == null) {
            var previous =
                    mapper.selectOne(
                            new QueryWrapper<ErpCostEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("request_id", c.requestId()));
            if (previous != null) {
                var old = entry(tenant, category, previous.getId(), action);
                if (!json.writeValueAsString(old.data()).equals(body))
                    throw conflict("请求编号已用于另一笔费用");
                return old;
            }
            var row = new ErpCostEntity();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenant);
            row.setDocumentNo(
                    new BusinessCodeGenerator().generate(BusinessCodeRule.millisecond("CST", 6)));
            row.setRequestId(c.requestId());
            row.setCategoryCode(category);
            row.setTitle(c.title());
            row.setTypeCode(c.typeCode());
            row.setCityCode(c.cityCode());
            row.setDepartmentId(c.departmentId());
            row.setOwnerEmployeeCode(c.ownerEmployeeCode());
            row.setWarehouseId(warehouse);
            row.setSourceWarehouseId(source);
            row.setCostDate(c.costDate());
            row.setAmount(c.amount());
            row.setPaymentStatusCode(c.paymentStatusCode());
            row.setPayerCode(null);
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
    public ErpCostDetailView transition(
            String tenant,
            String category,
            String id,
            ErpCostActionCommand c,
            String actor,
            String action) {
        lock(tenant);
        var old = entry(tenant, category, id, action);
        boolean confirm = action.endsWith(":confirm");
        transitionRule(
                old.statusCode(), confirm ? "CONFIRM" : "VOID", old.data().amount(), c.reason());
        if (mapper.update(
                        null,
                        new UpdateWrapper<ErpCostEntity>()
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
            String tenant, ErpCostDetailView v, String action, String actor, String reason) {
        var row = new ErpCostAuditEntity();
        row.setTenantId(tenant);
        row.setEntryId(v.id());
        row.setActionCode(action);
        row.setActorId(actor);
        row.setReason(reason);
        row.setSnapshotJson(json.writeValueAsString(v));
        audits.insert(row);
    }

    public List<ErpCostAuditView> history(String tenant, String id) {
        return audits
                .selectList(
                        new QueryWrapper<ErpCostAuditEntity>()
                                .eq("tenant_id", tenant)
                                .eq("entry_id", id)
                                .orderByDesc("id"))
                .stream()
                .map(
                        a ->
                                new ErpCostAuditView(
                                        a.getActionCode(),
                                        a.getActorId(),
                                        a.getReason(),
                                        a.getCreatedTime().toInstant(ZoneOffset.UTC),
                                        json.readValue(
                                                a.getSnapshotJson(), ErpCostDetailView.class)))
                .toList();
    }

    private ErpCostDetailView view(ErpCostEntity r) {
        return new ErpCostDetailView(
                r.getId(),
                r.getDocumentNo(),
                r.getCategoryCode(),
                r.getStatusCode(),
                r.getRevision(),
                json.readValue(r.getDataJson(), ErpCostCommand.class),
                Arrays.asList(json.readValue(r.getSourcesJson(), ErpCostSourceView[].class)),
                r.getWarehouseName(),
                r.getOwnerEmployeeName(),
                r.getCreatedTime().toInstant(ZoneOffset.UTC),
                r.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }

    public List<ErpCostOptionView> warehouses(String tenant, String keyword, String action) {
        var q =
                new QueryWrapper<
                                com.rigour.erp.infrastructure.persistence.entity
                                        .InternalInventoryWarehouseEntity>()
                        .eq("tenant_id", tenant)
                        .eq("deleted", 0)
                        .eq("status_code", "ACTIVE")
                        .like("warehouse_name", keyword);
        var p = scope.predicate(action, "id");
        bind(q, p.sql(), p.args());
        q.orderByAsc("warehouse_name").last("LIMIT 100");
        return referenceMapper.selectList(q).stream()
                .map(
                        w ->
                                new ErpCostOptionView(
                                        w.getId().toString(),
                                        w.getWarehouseName() + "（" + w.getWarehouseCode() + "）"))
                .toList();
    }

    public String warehouse(String tenant, Long id, String action) {
        scope.requireWarehouse(tenant, id, action);
        var w =
                referenceMapper.selectOne(
                        new QueryWrapper<
                                        com.rigour.erp.infrastructure.persistence.entity
                                                .InternalInventoryWarehouseEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("deleted", 0)
                                .eq("status_code", "ACTIVE"));
        if (w == null) throw bad("仓库不存在或已停用");
        return w.getWarehouseName();
    }

    public List<ErpCostSourceView> sources(
            String tenant, String leg, String keyword, List<Long> ids, String action) {
        if ("DELIVERY".equals(leg))
            return references.deliveries(keyword, ids).stream()
                    .filter(
                            s ->
                                    s.warehouseId() != null
                                            && scope.visibleDocument(
                                                    tenant,
                                                    s.warehouseId(),
                                                    "erp_inventory_warehouse",
                                                    action,
                                                    "id"))
                    .map(
                            s ->
                                    new ErpCostSourceView(
                                            s.id(),
                                            s.documentNo(),
                                            s.sourceType(),
                                            s.cityCode(),
                                            s.warehouseId(),
                                            s.warehouseId(),
                                            s.senderCode(),
                                            warehouse(tenant, s.warehouseId(), action),
                                            s.receiverCode(),
                                            s.receiverName(),
                                            s.statusCode()))
                    .toList();
        boolean purchase = "PURCHASE".equals(leg);
        var q = new QueryWrapper<Object>().eq("d.tenant_id", tenant).eq("d.deleted", 0);
        q.in(
                "d.status_code",
                purchase
                        ? List.of("SUBMITTED", "PARTIAL_IN", "COMPLETED", "IN_CONFIRMED")
                        : List.of("OUT_CONFIRMED", "IN_CONFIRMED"));
        var p =
                purchase
                        ? scope.predicate(action, "d.target_warehouse_id")
                        : scope.predicate(action, "d.source_warehouse_id", "d.target_warehouse_id");
        bind(q, p.sql(), p.args());
        if (!ids.isEmpty()) q.in("d.getId()", ids);
        else
            q.and(
                    w ->
                            w.like(purchase ? "d.procurement_no" : "d.transfer_no", keyword)
                                    .or()
                                    .like("w.warehouse_name", keyword));
        q.orderByDesc("d.getId()").last("LIMIT 100");
        return purchase ? referenceMapper.purchaseSources(q) : referenceMapper.transferSources(q);
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
            CostRules.transition(state, action, amount, reason);
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
