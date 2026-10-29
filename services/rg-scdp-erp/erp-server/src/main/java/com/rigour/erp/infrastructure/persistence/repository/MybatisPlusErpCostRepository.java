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
        // 与费用写入处于同一事务；失败回滚，确认、更正及作废均推进分析版本。
        mapper.advanceAnalyticsVersion(tenant);
    }

    private QueryWrapper<ErpCostEntity> where(String tenant, String category, String action) {
        var q =
                new QueryWrapper<ErpCostEntity>()
                        .eq("tenant_id", tenant)
                        .eq("category_code", category);
        var manual =
                scope.manualPredicate(
                        action, "MARKETING".equals(category) ? "EMPLOYEE" : "INVENTORY");
        if ("MARKETING".equals(category)) {
            var people = references.employees(action);
            q.and(
                    w -> {
                        w.and(
                                m -> {
                                    m.and(
                                            owner ->
                                                    owner.isNull("owner_employee_code")
                                                            .or()
                                                            .eq("owner_employee_code", ""));
                                    bind(m, manual.sql(), manual.args());
                                });
                        if (!people.isEmpty())
                            w.or().in(
                                            "owner_employee_code",
                                            people.stream()
                                                    .map(ErpCostEmployeeView::employeeCode)
                                                    .toList());
                    });
        } else {
            var p = scope.predicate(action, "warehouse_id", "source_warehouse_id");
            q.and(
                    w ->
                            w.and(
                                            m -> {
                                                m.isNull("warehouse_id")
                                                        .isNull("source_warehouse_id");
                                                bind(m, manual.sql(), manual.args());
                                            })
                                    .or(
                                            m -> {
                                                m.isNotNull("warehouse_id")
                                                        .isNotNull("source_warehouse_id");
                                                bind(m, p.sql(), p.args());
                                            }));
        }
        scope.applyCity(
                q, action, "city_code", "MARKETING".equals(category) ? "EMPLOYEE" : "INVENTORY");
        return q;
    }

    private QueryWrapper<ErpCostEntity> query(
            String tenant, String category, ErpCostQuery filter, String action) {
        var q = where(tenant, category, action);
        q.eq(text(filter.sourceSystem()), "source_system", filter.sourceSystem());
        if (Boolean.TRUE.equals(filter.missingAmount()))
            q.isNull("amount").ne("status_code", "VOID");
        if (text(filter.objectName())) {
            // 字段路径由业务类别固定，用户输入始终作为绑定参数。
            String[] paths =
                    switch (category) {
                        case "PURCHASE", "LOSS" ->
                                new String[] {"$.goods.productName", "$.goods.specification"};
                        case "RENTAL" -> new String[] {"$.rental.premisesName"};
                        default -> new String[] {"$.ownerName", "$.payeeName"};
                    };
            q.and(
                    names -> {
                        for (int i = 0; i < paths.length; i++) {
                            if (i > 0) names.or();
                            names.apply(
                                    "LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'"
                                            + paths[i]
                                            + "')),''))>0",
                                    filter.objectName().trim());
                        }
                        if ("RENTAL".equals(category))
                            names.or()
                                    .apply(
                                            "LOCATE({0},COALESCE(warehouse_name,''))>0",
                                            filter.objectName().trim());
                    });
        }
        if (text(filter.productName()))
            q.apply("LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.productName')),''))>0", filter.productName().trim());
        if (text(filter.specification()))
            q.apply("LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.specification')),''))>0", filter.specification().trim());
        if (text(filter.supplierName()))
            q.apply(
                    "LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.supplierName')),''))>0",
                    filter.supplierName().trim());
        if (text(filter.lossReason()))
            q.apply(
                    "LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.lossReason')),''))>0",
                    filter.lossReason().trim());
        if (text(filter.ownerName()))
            q.apply(
                    "LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.ownerName')),''))>0",
                    filter.ownerName().trim());
        if (text(filter.payeeName()))
            q.apply(
                    "LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.payeeName')),''))>0",
                    filter.payeeName().trim());
        if (text(filter.landlordName()))
            q.apply(
                    "LOCATE({0},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.rental.landlordName')),''))>0",
                    filter.landlordName().trim());
        if (text(filter.sourceDocumentNo())) {
            String pattern =
                    "%"
                            + filter.sourceDocumentNo()
                                    .trim()
                                    .replace("\\", "\\\\")
                                    .replace("%", "\\%")
                                    .replace("_", "\\_")
                            + "%";
            q.apply(
                    "(JSON_SEARCH(sources_json,'one',{0},NULL,'$[*].documentNo') IS NOT NULL OR"
                        + " JSON_SEARCH(sources_json,'one',{0},NULL,'$[*].originalNo') IS NOT NULL"
                        + " OR JSON_SEARCH(data_json,'one',{0},NULL,'$.goods.externalDocument.documentNo','$.externalDocuments[*].documentNo')"
                        + " IS NOT NULL)",
                    pattern);
        }
        q.eq(text(filter.costMonth()), "cost_month", filter.costMonth())
                .eq(filter.departmentId() != null, "department_id", filter.departmentId());
        if (text(filter.documentNo()))
            q.apply("LOCATE({0},document_no)>0", filter.documentNo().trim());
        if (text(filter.title())) q.apply("LOCATE({0},title)>0", filter.title().trim());
        if (text(filter.remark()))
            q.apply(
                    "LOCATE({0},JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.remark')))>0",
                    filter.remark().trim());
        if (text(filter.contractNo()))
            q.apply(
                    "LOCATE({0},JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.rental.contractNo')))>0",
                    filter.contractNo().trim());
        if ("RENTAL".equals(category) && text(filter.typeCode()))
            q.apply("premises_type={0}", filter.typeCode());
        q.eq(!"RENTAL".equals(category) && text(filter.typeCode()), "type_code", filter.typeCode())
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
        return new ErpCostPageView(
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
                        : c.goods() != null && c.goods().warehouseId() != null
                                ? c.goods().warehouseId()
                                : sources.isEmpty() ? null : sources.getFirst().targetWarehouseId();
        Long source = sources.isEmpty() ? warehouse : sources.getFirst().warehouseId();
        if (warehouse == null) warehouse = source;
        if (source == null) source = warehouse;
        scope.requireManual(
                action, "MARKETING".equals(category) ? "EMPLOYEE" : "INVENTORY", c.cityCode());
        if (!"MARKETING".equals(category) && warehouse != null && source != null)
            scope.requirePair(tenant, source, warehouse, action);
        if (c.rental() != null && c.costMonth() != null) {
            var duplicates =
                    where(tenant, category, action)
                            .eq("city_code", c.cityCode())
                            .eq("cost_month", c.costMonth())
                            .eq("premises_type", c.rental().premisesType())
                            .ne("status_code", "VOID");
            if (id != null) duplicates.ne("id", id);
            // 同 requestId 的网络重试由下方幂等逻辑处理，不视为重复登记。
            duplicates.ne("request_id", c.requestId());
            if (c.rental().warehouseId() != null)
                duplicates.eq("warehouse_id", c.rental().warehouseId());
            else
                duplicates
                        .isNull("warehouse_id")
                        .apply(
                                "JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.rental.premisesName'))={0}",
                                c.rental().premisesName());
            if (mapper.selectCount(duplicates) > 0 && !text(c.reason()))
                throw bad("同场地同月份已有费用，请填写重复登记或补差说明");
        }
        if ("LOSS".equals(category) && c.goods() != null && !text(c.reason())) {
            var duplicates =
                    where(tenant, category, action)
                            .eq("city_code", c.cityCode())
                            .eq("cost_date", c.costDate())
                            .ne("status_code", "VOID");
            if (id != null) duplicates.ne("id", id);
            duplicates.ne("request_id", c.requestId());
            if (warehouse == null) duplicates.isNull("warehouse_id");
            else duplicates.eq("warehouse_id", warehouse);
            if (c.amount() == null) duplicates.isNull("amount");
            else duplicates.eq("amount", c.amount());
            duplicates
                    .apply(
                            "JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.productName'))={0}",
                            c.goods().productName())
                    .apply(
                            "JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.unit'))={0}",
                            c.goods().unit())
                    .apply(
                            "COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.specification')),'null'),'')={0}",
                            java.util.Objects.toString(c.goods().specification(), ""))
                    .apply(
                            "CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.quantity')) AS"
                                    + " DECIMAL(20,6))={0}",
                            c.goods().quantity());
            if (warehouse == null)
                duplicates.apply(
                        "JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.location'))={0}",
                        c.goods().location());
            if (mapper.selectCount(duplicates) > 0) throw bad("同商品、日期、地点、数量和金额已有损耗，请填写补充登记说明后保存");
        }
        String body = json.writeValueAsString(c);
        var values =
                new UpdateWrapper<ErpCostEntity>()
                        .set("source_system", sourceSystem(c, sources))
                        .set("premises_type", c.rental() == null ? null : c.rental().premisesType())
                        .set("title", c.title())
                        .set("type_code", c.typeCode())
                        .set("city_code", c.cityCode())
                        .set("department_id", c.departmentId())
                        .set("owner_employee_code", c.ownerEmployeeCode())
                        .set("warehouse_id", warehouse)
                        .set("source_warehouse_id", source)
                        .set("cost_date", c.costDate())
                        .set("cost_month", c.costMonth())
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
            row.setSourceSystem(sourceSystem(c, sources));
            row.setPremisesType(c.rental() == null ? null : c.rental().premisesType());
            row.setTitle(c.title());
            row.setTypeCode(c.typeCode());
            row.setCityCode(c.cityCode());
            row.setDepartmentId(c.departmentId());
            row.setOwnerEmployeeCode(c.ownerEmployeeCode());
            row.setWarehouseId(warehouse);
            row.setSourceWarehouseId(source);
            row.setCostDate(c.costDate());
            row.setCostMonth(c.costMonth());
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
        if (old.data().goods() != null
                && c.goods() != null
                && (c.goods().purchaseOrderId() != null
                        || old.data().goods().externalDocument() != null
                                && old.data().goods().externalDocument().linkedId() != null)) {
            var original = old.data().goods().externalDocument();
            var next = c.goods().externalDocument();
            if (original != null
                    && (next == null
                            || !java.util.Objects.equals(
                                    original.sourceSystem(), next.sourceSystem())
                            || !java.util.Objects.equals(original.documentNo(), next.documentNo())))
                throw bad("补关联必须保留原始来源系统和原始单号");
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
        if (confirm && old.data().goods() != null && old.data().goods().purchaseOrderId() != null) {
            var current =
                    sources(
                            tenant,
                            "PURCHASE",
                            "",
                            List.of(old.data().goods().purchaseOrderId()),
                            action);
            if (!new java.util.HashSet<>(current).equals(new java.util.HashSet<>(old.sources())))
                throw conflict("采购来源已变化或无权限，请重新编辑后确认");
        }
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
                r.getUpdatedTime().toInstant(ZoneOffset.UTC),
                r.getCreatedBy(),
                r.getUpdatedBy());
    }

    public List<com.rigour.erp.api.v1.model.ErpCostReferenceView> references(
            String tenant,
            String kind,
            String keyword,
            Long id,
            Long parentId,
            int begin,
            String action) {
        String alias =
                switch (kind) {
                    case "products" -> "p";
                    case "variants" -> "v";
                    case "suppliers" -> "s";
                    default -> throw bad("未知引用类型");
                };
        var q =
                new QueryWrapper<Object>()
                        .eq(alias + ".tenant_id", tenant)
                        .eq(alias + ".deleted", 0);
        if ("suppliers".equals(kind)) q.eq("s.status_code", "ACTIVE");
        if (id != null) q.eq(alias + ".id", id);
        if ("variants".equals(kind)) q.eq("v.product_id", parentId);
        if (text(keyword)) {
            String name = "suppliers".equals(kind) ? "s.supplier_name" : "p.product_name";
            String code =
                    "products".equals(kind)
                            ? "p.product_code"
                            : "variants".equals(kind) ? "v.variant_code" : "s.supplier_code";
            q.and(
                    w -> {
                        w.like(name, keyword).or().like(code, keyword);
                        if ("variants".equals(kind))
                            w.or().like("v.specification_snapshot", keyword);
                    });
        }
        q.orderByAsc(alias + ".id").last("LIMIT 50 OFFSET " + Math.max(0, begin));
        return switch (kind) {
            case "products" -> referenceMapper.products(q);
            case "variants" -> referenceMapper.variants(q);
            default -> referenceMapper.suppliers(q);
        };
    }

    public List<ErpCostOptionView> warehouses(String tenant, String keyword, String action) {
        var q =
                new QueryWrapper<
                                com.rigour.erp.infrastructure.persistence.entity
                                        .InternalInventoryWarehouseEntity>()
                        .eq("tenant_id", tenant)
                        .eq("deleted", 0)
                        .eq("status_code", "ACTIVE")
                        .and(
                                w ->
                                        w.like("warehouse_name", keyword)
                                                .or()
                                                .like("warehouse_code", keyword));
        var p = scope.predicate(action, "id");
        bind(q, p.sql(), p.args());
        q.orderByAsc("warehouse_name").last("LIMIT 100");
        return referenceMapper.selectList(q).stream()
                .map(
                        w ->
                                new ErpCostOptionView(
                                        w.getId().toString(),
                                        w.getWarehouseName() + "（" + w.getWarehouseCode() + "）",
                                        w.getWarehouseName(),
                                        w.getWarehouseTypeCode(),
                                        w.getRegionCode(),
                                        w.getAddress()))
                .toList();
    }

    public String warehouse(String tenant, Long id, String action) {
        return warehouseOption(tenant, id, action).name();
    }

    public ErpCostOptionView warehouseOption(String tenant, Long id, String action) {
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
        return new ErpCostOptionView(
                w.getId().toString(),
                w.getWarehouseName(),
                w.getWarehouseName(),
                w.getWarehouseTypeCode(),
                w.getRegionCode(),
                w.getAddress());
    }

    public List<ErpCostSourceView> sources(
            String tenant, String leg, String keyword, List<Long> ids, String action) {
        return sources(tenant, leg, keyword, ids, action, null, 0);
    }

    public List<ErpCostSourceView> sources(
            String tenant,
            String leg,
            String keyword,
            List<Long> ids,
            String action,
            String city,
            int begin) {
        if ("DELIVERY".equals(leg))
            return references.deliveries(keyword, ids, city, begin, action).stream()
                    .filter(
                            s ->
                                    s.warehouseId() == null
                                            || scope.visibleDocument(
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
                                            s.warehouseId() == null
                                                    ? null
                                                    : warehouse(tenant, s.warehouseId(), action),
                                            s.receiverCode(),
                                            s.receiverName(),
                                            s.statusCode(),
                                            s.sourceSystem(),
                                            s.originalNo(),
                                            s.documentDate(),
                                            s.warehouseId() == null
                                                    ? null
                                                    : warehouseOption(
                                                                    tenant, s.warehouseId(), action)
                                                            .address(),
                                            s.destinationAddress(),
                                            s.warehouseId() == null
                                                    ? null
                                                    : warehouseOption(
                                                                    tenant, s.warehouseId(), action)
                                                            .warehouseTypeCode(),
                                            null))
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
        scope.applyCity(q, action, "w.region_code");
        if (!ids.isEmpty()) q.in("d.id", ids);
        else
            q.and(
                    w ->
                            w.like(purchase ? "d.procurement_no" : "d.transfer_no", keyword)
                                    .or()
                                    .like("w.warehouse_name", keyword)
                                    .or(purchase)
                                    .like(purchase, "s.supplier_name", keyword)
                                    .or(purchase)
                                    .like(purchase, "d.source_document_no", keyword));
        q.eq(text(city), "w.region_code", city)
                .orderByDesc("d.id")
                .last(ids.isEmpty() ? "LIMIT 50 OFFSET " + Math.max(0, begin) : "LIMIT 100");
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

    private static String sourceSystem(ErpCostCommand c, List<ErpCostSourceView> sources) {
        java.util.Set<String> values = new java.util.HashSet<>();
        if (c.goods() != null && c.goods().externalDocument() != null)
            values.add(c.goods().externalDocument().sourceSystem());
        if (c.externalDocuments() != null)
            c.externalDocuments().forEach(d -> values.add(d.sourceSystem()));
        for (var source : sources) {
            if (c.goods() != null
                    && c.goods().externalDocument() != null
                    && java.util.Objects.equals(
                            c.goods().externalDocument().linkedId(), source.id())) continue;
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
