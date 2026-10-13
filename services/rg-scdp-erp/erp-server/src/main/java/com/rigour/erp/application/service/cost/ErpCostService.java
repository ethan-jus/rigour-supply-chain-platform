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
            case "marketing" -> "MARKETING";
            case "warehouse-rentals" -> "RENTAL";
            default -> throw bad("未知成本类别");
        };
    }

    public static String permission(String category, String action) {
        return "erp:cost:"
                + switch (category) {
                    case "RENTAL" -> "rental";
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
        String c = category(value), p = permission(c, "read");
        return store.warehouses(actor(p).tenantId().toString(), keyword, p);
    }

    public ErpCostPageView entries(String value, ErpCostQuery q) {
        String category = category(value), p = permission(category, "read");
        var a = actor(p);
        if (q.dateFrom() != null && q.dateTo() != null && q.dateTo().isBefore(q.dateFrom()))
            throw bad("截止日期不能早于开始日期");
        return store.entries(
                a.tenantId().toString(),
                category,
                new ErpCostQuery(
                        Math.max(0, q.begin()),
                        q.step() <= 0 ? 20 : Math.min(200, q.step()),
                        q.keyword(),
                        q.typeCode(),
                        q.cityCode(),
                        q.statusCode(),
                        q.paymentStatusCode(),
                        q.dateFrom(),
                        q.dateTo(),
                        q.warehouseId(),
                        q.ownerEmployeeCode()),
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

    public ErpCostDetailView save(String value, String id, ErpCostCommand c) {
        String category = category(value), p = permission(category, "write");
        var a = actor(p);
        String t = a.tenantId().toString();
        if (c == null) throw bad("请填写费用");
        required(c.title(), 200, "费用事项");
        required(c.cityCode(), 64, "归属城市编码");
        required(c.cityName(), 120, "归属城市");
        if (c.costDate() == null) throw bad("请选择费用日期");
        text(c.remark(), 1000, "备注");
        text(c.reason(), 500, "变更原因");
        rules(() -> CostRules.money(c.amount(), true));
        valid(t, "COST_PAYMENT_STATUS", c.paymentStatusCode());
        if ("PAID".equals(c.paymentStatusCode()) && c.amount() == null) throw bad("已付款费用必须填写金额");
        if (id == null) {
            try {
                UUID.fromString(c.requestId());
            } catch (Exception e) {
                throw bad("requestId格式无效");
            }
        } else if (c.revision() == null || c.revision() < 1) throw bad("缺少费用版本，请刷新");
        List<ErpCostSourceView> sources = List.of();
        String warehouseName = null, employeeName = null;
        if ("RENTAL".equals(category)) {
            if (c.rental() == null) throw bad("租赁参数无效");
            var r = c.rental();
            if (r.warehouseId() == null) throw bad("请选择仓库");
            warehouseName = store.warehouse(t, r.warehouseId(), p);
            if (r.area() == null
                    || r.area().signum() <= 0
                    || r.area().scale() > 2
                    || r.area().compareTo(new java.math.BigDecimal("999999999")) > 0)
                throw bad("请填写有效租赁面积");
            required(r.contractNo(), 100, "合同编号");
            if (r.leaseFrom() == null || r.leaseTo() == null || r.leaseTo().isBefore(r.leaseFrom()))
                throw bad("租期无效");
            rules(() -> CostRules.money(r.deposit(), true));
        } else {
            if (c.rental() != null) throw bad("营销费用不能携带租赁信息");
            valid(t, "MARKETING_EXPENSE_TYPE", c.typeCode());
            required(c.ownerEmployeeCode(), 50, "经办员工");
            var employee =
                    references.employees(p).stream()
                            .filter(e -> e.employeeCode().equals(c.ownerEmployeeCode()))
                            .findFirst()
                            .orElseThrow(() -> bad("经办员工不存在或无权限"));
            if (!Objects.equals(employee.departmentId(), c.departmentId())) throw bad("归属部门与员工不一致");
            employeeName = employee.employeeName();
        }
        return store.save(
                t,
                category,
                id,
                c,
                sources,
                warehouseName,
                employeeName,
                a.principalId().toString(),
                p);
    }

    public ErpCostDetailView action(
            String value, String id, ErpCostActionCommand c, boolean confirm) {
        String category = category(value), p = permission(category, confirm ? "confirm" : "void");
        var a = actor(p);
        if (c == null || c.revision() < 1) throw bad("缺少费用版本");
        text(c.reason(), 500, "操作原因");
        return store.transition(
                a.tenantId().toString(), category, id, c, a.principalId().toString(), p);
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

    public static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
