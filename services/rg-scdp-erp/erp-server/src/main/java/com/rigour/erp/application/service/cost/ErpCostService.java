package com.rigour.erp.application.service.cost;

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
import com.rigour.erp.application.port.out.ErpProductDictionary;
import com.rigour.erp.domain.cost.CostRules;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public final class ErpCostService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ErpCostService.class);
    private final ErpCostStore store;
    private final ErpProductDictionary dictionary;
    private final CostReferenceSource references;

    public ErpCostService(
            ErpCostStore store, ErpProductDictionary dictionary, CostReferenceSource references) {
        this.store = store;
        this.dictionary = dictionary;
        this.references = references;
    }

    public static String category(String value) {
        return switch (value) {
            case "purchases" -> "PURCHASE";
            case "losses" -> "LOSS";
            case "marketing" -> "MARKETING";
            case "warehouse-rentals" -> "RENTAL";
            default -> throw bad("未知成本类别");
        };
    }

    public static String permission(String category, String action) {
        return "erp:cost:"
                + switch (category) {
                    case "RENTAL" -> "rental";
                    case "PURCHASE" -> "purchase";
                    case "LOSS" -> "loss";
                    default -> "marketing";
                }
                + ":"
                + action;
    }

    private static CallerIdentity actor(String permission) {
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        return actor;
    }

    public List<ErpCostOptionView> warehouses(String value, String keyword) {
        String p = referencePermission(value);
        return store.warehouses(actor(p).tenantId().toString(), keyword, p);
    }

    public ErpCostPageView entries(String value, ErpCostQuery q) {
        return entries(value, q, "read");
    }

    public ErpCostPageView export(String value, ErpCostQuery q) {
        var result = entries(value, q, "export");
        var caller = AuthorizationContext.requireCurrent();
        log.info(
                "cost_export tenant={} actor={} category={} begin={} returned={} total={}",
                caller.tenantId(),
                caller.principalId(),
                value,
                q.begin(),
                result.items().size(),
                result.total());
        return result;
    }

    private ErpCostPageView entries(String value, ErpCostQuery q, String action) {
        String category = category(value), p = permission(category, action);
        var a = actor(p);
        month(q.costMonth());
        text(q.objectName(), 200, "业务对象");
        text(q.documentNo(), 100, "费用单号");
        text(q.title(), 200, "费用事项");
        text(q.remark(), 1000, "备注");
        text(q.contractNo(), 100, "租赁合同号");
        if (q.dateFrom() != null && q.dateTo() != null && q.dateTo().isBefore(q.dateFrom()))
            throw bad("截止日期不能早于开始日期");
        return store.entries(
                a.tenantId().toString(),
                category,
                new ErpCostQuery(
                        Math.max(0, q.begin()),
                        q.step() <= 0 ? 20 : Math.min(200, q.step()),
                        q.documentNo(),
                        q.title(),
                        q.remark(),
                        q.contractNo(),
                        q.typeCode(),
                        q.cityCode(),
                        q.statusCode(),
                        q.paymentStatusCode(),
                        q.dateFrom(),
                        q.dateTo(),
                        q.warehouseId(),
                        q.ownerEmployeeCode(),
                        q.costMonth(),
                        q.objectName(),
                        q.departmentId(),
                        q.sourceSystem(),
                        q.sourceDocumentNo(),
                        q.missingAmount(),
                        q.supplierName(),
                        q.lossReason(),
                        q.ownerName(),
                        q.payeeName(),
                        q.landlordName()),
                p);
    }

    public ErpCostDetailView entry(String value, String id) {
        String c = category(value), p = permission(c, "read");
        return store.entry(actor(p).tenantId().toString(), c, id, p);
    }

    public List<ErpCostAuditView> history(String value, String id) {
        var row = entry(value, id);
        return store.history(AuthorizationContext.requireCurrent().tenantId().toString(), row.id());
    }

    public List<ErpCostEmployeeView> employees() {
        actor(permission("MARKETING", "read"));
        return references.employees(permission("MARKETING", "read"));
    }

    public ErpCostDetailView save(String value, String id, ErpCostCommand command) {
        return operation(
                id == null ? "CREATE" : "UPDATE", id, () -> saveValidated(value, id, command));
    }

    private ErpCostDetailView saveValidated(String value, String id, ErpCostCommand c) {
        String category = category(value), p = permission(category, "write");
        var a = actor(p);
        String t = a.tenantId().toString();
        if (c == null) throw bad("请填写费用");
        if (id == null) {
            try {
                UUID.fromString(c.requestId());
            } catch (Exception e) {
                throw bad("requestId格式无效");
            }
        } else if (c.revision() == null || c.revision() < 1) throw bad("缺少费用版本，请刷新");
        var validated = validate(t, category, c, p);
        return store.save(
                t,
                category,
                id,
                c,
                validated.sources(),
                validated.warehouseName(),
                validated.employeeName(),
                a.principalId().toString(),
                p);
    }

    private record ValidatedCost(
            List<ErpCostSourceView> sources, String warehouseName, String employeeName) {}

    /** 保存和确认共用完整校验，旧草稿不能绕过新增字段与引用范围。 */
    private ValidatedCost validate(String t, String category, ErpCostCommand c, String p) {
        if (c == null) throw bad("请填写费用");
        required(c.title(), 200, "费用事项");
        required(c.cityCode(), 64, "归属城市编码");
        required(c.cityName(), 120, "归属城市");
        if (c.costDate() == null) throw bad("请选择费用日期");
        text(c.remark(), 1000, "备注");
        text(c.reason(), 500, "变更原因");
        rules(() -> CostRules.money(c.amount(), false));
        month(c.costMonth());
        text(c.ownerName(), 120, "经办人");
        text(c.payeeName(), 200, "收款方");
        if (c.paymentStatusCode() != null && !c.paymentStatusCode().isBlank())
            valid(t, "COST_PAYMENT_STATUS", c.paymentStatusCode());
        if (!Objects.equals(references.city(c.cityCode(), p), c.cityName()))
            throw bad("城市名称与授权目录不一致，请重新选择");
        supplementary(c, category);
        if ("PAID".equals(c.paymentStatusCode()) && c.amount() == null) throw bad("已付款费用必须填写金额");
        List<ErpCostSourceView> sources = List.of();
        String warehouseName = null, employeeName = null;
        if ("RENTAL".equals(category)) {
            if (c.rental() == null || c.goods() != null) throw bad("场地参数无效");
            var r = c.rental();
            String type = r.premisesType();
            if (!java.util.Set.of("WAREHOUSE", "OFFICE").contains(Objects.toString(type, "")))
                throw bad("历史场地类型待核对，请选择仓储或办公室");
            valid(t, "COST_PREMISES_TYPE", type);
            if ("OFFICE".equals(type) && r.warehouseId() != null) throw bad("办公室不能关联库存仓库");
            if (r.warehouseId() != null) warehouseName = store.warehouse(t, r.warehouseId(), p);
            else {
                required(r.premisesName(), 120, "场地名称");
                warehouseName = r.premisesName();
            }
            if (r.warehouseId() != null
                    && r.premisesName() != null
                    && !r.premisesName().isBlank()
                    && !Objects.equals(r.premisesName(), warehouseName)) throw bad("仓库名称与关联主档不一致");
            if (r.area() == null
                    || r.area().signum() <= 0
                    || r.area().scale() > 2
                    || r.area().precision() - r.area().scale() > 9) throw bad("请填写有效面积");
            text(r.contractNo(), 100, "合同编号");
            text(r.address(), 500, "场地地址");
            text(r.landlordName(), 200, "出租方");
            if ((r.leaseFrom() == null) != (r.leaseTo() == null)
                    || r.leaseFrom() != null && r.leaseTo().isBefore(r.leaseFrom()))
                throw bad("租期无效");
            required(c.costMonth(), 7, "费用月份");
            rules(() -> CostRules.money(r.deposit(), false));
        } else if ("MARKETING".equals(category)) {
            if (c.rental() != null || c.goods() != null) throw bad("营销参数无效");
            valid(t, "MARKETING_EXPENSE_TYPE", c.typeCode());
            if (c.ownerEmployeeCode() != null && !c.ownerEmployeeCode().isBlank()) {
                var employee =
                        references.employees(p).stream()
                                .filter(e -> e.employeeCode().equals(c.ownerEmployeeCode()))
                                .findFirst()
                                .orElseThrow(() -> bad("经办员工不存在或无权限"));
                if (!Objects.equals(employee.departmentId(), c.departmentId()))
                    throw bad("归属部门与员工不一致");
                employeeName = employee.employeeName();
            } else {
                required(c.ownerName(), 120, "经办人");
                employeeName = c.ownerName();
            }
        } else {
            if (c.goods() == null || c.rental() != null) throw bad("货品参数无效");
            var g = c.goods();
            required(g.productName(), 200, "商品名称");
            required(g.unit(), 64, "单位");
            text(g.specification(), 500, "规格");
            text(g.supplierName(), 200, "供应商");
            text(g.location(), 500, "发生地点");
            text(g.lossReason(), 500, "损耗原因");
            text(g.amountReason(), 500, "金额说明");
            decimal(g.unitPrice(), false, "进货单价");
            decimal(g.quantity(), "LOSS".equals(category), "数量");
            if ("PURCHASE".equals(category) && g.unitPrice() == null) throw bad("请填写进货单价");
            if ("LOSS".equals(category)) {
                required(g.lossReason(), 500, "损耗原因");
                if (g.warehouseId() == null) required(g.location(), 500, "损耗发生地点");
            }
            if (g.quantity() != null && g.quantity().signum() <= 0) throw bad("数量必须大于零");
            if (g.productId() != null) {
                var product = reference(t, "products", g.productId(), null, p);
                if (!Objects.equals(product.name(), g.productName()))
                    throw bad("商品名称与关联主档不一致，请重新选择");
            }
            if (g.productId() != null
                    && g.variantId() == null
                    && store.references(t, "variants", "", null, g.productId(), 0, p).size() > 1)
                throw bad("该商品有多个 SKU，请选择实际规格");
            if (g.variantId() != null) {
                if (g.productId() == null) throw bad("请先关联商品");
                var variant = reference(t, "variants", g.variantId(), g.productId(), p);
                if (!Objects.equals(variant.specification(), g.specification())
                        || !Objects.equals(variant.unit(), g.unit()))
                    throw bad("规格或单位与 SKU 不一致，请重新选择");
            }
            if (g.supplierId() != null
                    && !Objects.equals(
                            reference(t, "suppliers", g.supplierId(), null, p).name(),
                            g.supplierName())) throw bad("供应商名称与关联主档不一致");
            if (g.warehouseId() != null) warehouseName = store.warehouse(t, g.warehouseId(), p);
            if (g.externalDocument() != null) {
                if (!Objects.equals(g.externalDocument().linkedId(), g.purchaseOrderId()))
                    throw bad("补关联采购单与外部凭据关联 ID 不一致");
                externalDocument(g.externalDocument(), "PURCHASE");
            }
            if (g.purchaseOrderId() != null) {
                sources = store.sources(t, "PURCHASE", "", List.of(g.purchaseOrderId()), p);
                if (sources.size() != 1) throw bad("采购单不存在、未生效或无访问权限");
                var source = sources.getFirst();
                if (g.externalDocument() != null
                        && (!Objects.equals(g.externalDocument().senderName(), source.senderName())
                                || !Objects.equals(
                                        g.externalDocument().receiverName(), source.receiverName())
                                || !Objects.equals(
                                        g.externalDocument().documentNo(),
                                        source.originalNo() == null
                                                ? source.documentNo()
                                                : source.originalNo())))
                    required(g.externalDocument().differenceReason(), 500, "补关联单据差异说明");
                if (!Objects.equals(c.cityCode(), source.cityCode())
                        || g.warehouseId() != null
                                && !Objects.equals(g.warehouseId(), source.targetWarehouseId()))
                    throw bad("城市、仓库须与采购单一致");
                if (g.supplierId() != null
                        && !Objects.equals(
                                reference(t, "suppliers", g.supplierId(), null, p).code(),
                                source.senderCode())) throw bad("供应商须与采购单一致");
            }
        }
        if (!("MARKETING".equals(category)
                        && c.ownerEmployeeCode() != null
                        && !c.ownerEmployeeCode().isBlank())
                && (c.departmentId() != null
                        || c.ownerEmployeeCode() != null && !c.ownerEmployeeCode().isBlank()))
            throw bad("手工记录按录入人归属，不接受未经关联核验的部门或员工编号");
        return new ValidatedCost(sources, warehouseName, employeeName);
    }

    public ErpCostDetailView action(
            String value, String id, ErpCostActionCommand command, boolean confirm) {
        return operation(
                confirm ? "CONFIRM" : "VOID",
                id,
                () -> actionValidated(value, id, command, confirm));
    }

    private ErpCostDetailView actionValidated(
            String value, String id, ErpCostActionCommand c, boolean confirm) {
        String category = category(value), p = permission(category, confirm ? "confirm" : "void");
        var a = actor(p);
        if (c == null || c.revision() < 1) throw bad("缺少费用版本");
        text(c.reason(), 500, "操作原因");
        if (confirm) {
            var current = store.entry(a.tenantId().toString(), category, id, p);
            validate(a.tenantId().toString(), category, current.data(), p);
            validateConfirmation(category, current.data());
        }
        return store.transition(
                a.tenantId().toString(), category, id, c, a.principalId().toString(), p);
    }

    private static void validateConfirmation(String category, ErpCostCommand command) {
        if ("PURCHASE".equals(category)) {
            var goods = command.goods();
            if (goods == null) throw bad("请补齐进货商品");
            decimal(goods.quantity(), true, "进货数量");
            required(goods.supplierName(), 200, "供应商");
            if (goods.unitPrice() == null) throw bad("请填写进货单价");
            decimal(goods.unitPrice(), false, "进货单价");
            if (goods.unitPrice() != null && goods.unitPrice().signum() == 0)
                required(goods.amountReason(), 500, "零单价说明");
            if (command.amount() != null
                    && goods.unitPrice() != null
                    && goods.quantity() != null
                    && command.amount()
                                    .compareTo(
                                            goods.unitPrice()
                                                    .multiply(goods.quantity())
                                                    .setScale(2, java.math.RoundingMode.HALF_UP))
                            != 0) required(goods.amountReason(), 500, "金额与单价乘数量不一致的说明");
        }
        if ("RENTAL".equals(category)) required(command.costMonth(), 7, "费用月份");
    }

    private static void supplementary(ErpCostCommand command, String category) {
        if (command.marketing() != null) {
            if (!"MARKETING".equals(category)) throw bad("非营销记录不能携带营销信息");
            var marketing = command.marketing();
            text(marketing.channelName(), 200, "投放渠道");
            if ((marketing.serviceFrom() == null) != (marketing.serviceTo() == null)
                    || marketing.serviceFrom() != null
                            && marketing.serviceTo().isBefore(marketing.serviceFrom()))
                throw bad("活动服务期间无效");
        }
        if (command.externalDocuments() == null) return;
        if (!java.util.Set.of("RENTAL", "MARKETING").contains(category)
                && !command.externalDocuments().isEmpty()) throw bad("进货和损耗来源应登记在商品来源中");
        if (command.externalDocuments().size() > 20) throw bad("最多20份补充凭据");
        var keys = new java.util.HashSet<String>();
        for (var document : command.externalDocuments()) {
            if (document == null) throw bad("凭据不能为空");
            if (!java.util.Set.of("DHB", "MANUAL", "OTHER")
                    .contains(Objects.toString(document.sourceSystem(), ""))) throw bad("请选择凭据来源");
            required(document.documentType(), 64, "凭据类型");
            required(document.documentNo(), 100, "凭据单号");
            if (document.documentDate() == null) throw bad("请填写凭据日期");
            if (document.linkedId() != null) throw bad("租赁和营销凭据不能关联采购或销售单 ID");
            if ("OTHER".equals(document.sourceSystem()))
                required(document.sourceSystemName(), 120, "其他系统名称");
            text(document.senderName(), 200, "凭据对方");
            text(document.receiverName(), 200, "凭据我方");
            text(document.remark(), 500, "凭据备注");
            if (!keys.add(document.sourceSystem() + "|" + document.documentNo()))
                throw bad("补充凭据不能重复");
        }
    }

    private static String referencePermission(String value) {
        return "logistics".equals(value)
                ? "order:logistics-cost:read"
                : permission(category(value), "read");
    }

    public List<com.rigour.erp.api.v1.model.ErpCostReferenceView> references(
            String value, String kind, String keyword, Long parentId, int begin) {
        String p = referencePermission(value);
        var a = actor(p);
        text(keyword, 200, "搜索关键字");
        if ("variants".equals(kind) && parentId == null) throw bad("请选择商品");
        return store.references(
                a.tenantId().toString(), kind, keyword, null, parentId, Math.max(0, begin), p);
    }

    public List<ErpCostSourceView> purchaseOrders(String value, String keyword) {
        String p = permission(category(value), "read");
        var a = actor(p);
        text(keyword, 200, "搜索关键字");
        return store.sources(a.tenantId().toString(), "PURCHASE", keyword, List.of(), p);
    }

    private com.rigour.erp.api.v1.model.ErpCostReferenceView reference(
            String tenant, String kind, Long id, Long parent, String action) {
        return store.references(tenant, kind, "", id, parent, 0, action).stream()
                .findFirst()
                .orElseThrow(() -> bad("关联资料不存在或无权限，请重新选择"));
    }

    private static void month(String value) {
        if (value == null || value.isBlank()) return;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
            java.time.YearMonth.parse(value);
        } catch (RuntimeException e) {
            throw bad("费用月份格式应为 YYYY-MM");
        }
    }

    private static void decimal(java.math.BigDecimal value, boolean required, String label) {
        if (value == null) {
            if (required) throw bad("请填写" + label);
            return;
        }
        if (value.signum() < 0
                || required && value.signum() == 0
                || value.scale() > 6
                || value.precision() - value.scale() > 14) throw bad(label + "最多六位小数和十四位整数，数量须大于零");
    }

    private static void externalDocument(
            com.rigour.erp.api.v1.model.ErpCostExternalDocument d, String expected) {
        if (d == null) throw bad("请录入外部来源单据");
        if (!java.util.Set.of("DHB", "MANUAL", "OTHER")
                .contains(d.sourceSystem() == null ? "" : d.sourceSystem())) throw bad("请选择单据来源");
        if (!expected.equals(d.documentType())) throw bad("外部单据类型与费用类型不一致");
        required(d.documentNo(), 100, "外部单号");
        if (d.documentDate() == null) throw bad("请选择外部单据日期");
        required(d.senderName(), 200, "单据发货方");
        required(d.receiverName(), 200, "单据收货方");
        text(d.remark(), 500, "单据备注");
        text(d.differenceReason(), 500, "关联差异说明");
        if ("OTHER".equals(d.sourceSystem())) required(d.sourceSystemName(), 120, "其他系统名称");
    }

    private void valid(String tenant, String code, String value) {
        if (value == null || !dictionary.validCodes(tenant, code).contains(value))
            throw bad("请选择启用的" + code + "字典项");
    }

    private static void required(String value, int max, String name) {
        if (value == null || value.isBlank()) throw bad("请填写" + name);
        text(value, max, name);
    }

    private static void text(String value, int max, String name) {
        if (value != null && value.length() > max) throw bad(name + "长度不能超过" + max);
    }

    private static void rules(Runnable runnable) {
        try {
            runnable.run();
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    private ErpCostDetailView operation(
            String operation, String id, java.util.function.Supplier<ErpCostDetailView> work) {
        var caller = AuthorizationContext.requireCurrent();
        long started = System.nanoTime();
        try {
            var saved = work.get();
            log.info(
                    "cost_operation operation={} tenant={} actor={} entry={} revision={}"
                            + " elapsedMs={}",
                    operation,
                    caller.tenantId(),
                    caller.principalId(),
                    saved == null ? id : saved.id(),
                    saved == null ? null : saved.revision(),
                    (System.nanoTime() - started) / 1_000_000);
            return saved;
        } catch (BusinessException | AuthorizationDeniedException e) {
            log.warn(
                    "cost_operation_rejected operation={} tenant={} actor={} entry={} failure={}"
                            + " reason={}",
                    operation,
                    caller.tenantId(),
                    caller.principalId(),
                    id,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error(
                    "cost_operation_failed operation={} tenant={} actor={} entry={}",
                    operation,
                    caller.tenantId(),
                    caller.principalId(),
                    id,
                    e);
            throw e;
        }
    }

    public static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    public List<ErpCostSourceView> purchaseOrders(
            String value, String keyword, String city, int begin) {
        String action = permission(category(value), "read");
        var caller = actor(action);
        text(keyword, 200, "搜索关键字");
        text(city, 64, "城市");
        if (begin < 0 || begin > 100000) throw bad("查询位置无效");
        if (city != null && !city.isBlank()) references.city(city, action);
        return store.sources(
                caller.tenantId().toString(), "PURCHASE", keyword, List.of(), action, city, begin);
    }
}
