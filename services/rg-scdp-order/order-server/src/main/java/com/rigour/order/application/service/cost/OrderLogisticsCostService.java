package com.rigour.order.application.service.cost;

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
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public final class OrderLogisticsCostService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderLogisticsCostService.class);
    private final OrderLogisticsCostStore store;
    private final OrderLogisticsReferences dictionary;

    public OrderLogisticsCostService(
            OrderLogisticsCostStore store, OrderLogisticsReferences dictionary) {
        this.store = store;
        this.dictionary = dictionary;
    }

    public static String category(String value) {
        return "LOGISTICS";
    }

    public static String permission(String category, String action) {
        return "order:logistics-cost:" + action;
    }

    private static CallerIdentity actor(String permission) {
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        return actor;
    }

    public LogisticsCostPageView entries(String value, LogisticsCostQuery q) {
        return entries(value, q, "read");
    }

    public LogisticsCostPageView export(String value, LogisticsCostQuery q) {
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

    private LogisticsCostPageView entries(String value, LogisticsCostQuery q, String action) {
        String category = category(value), p = permission(category, action);
        var a = actor(p);
        text(q.objectName(), 200, "业务对象");
        text(q.documentNo(), 100, "费用单号");
        text(q.title(), 200, "费用事项");
        text(q.remark(), 1000, "备注");
        text(q.trackingNo(), 100, "运单号");
        text(q.sourceDocumentNo(), 100, "来源单号");
        if (q.dateFrom() != null && q.dateTo() != null && q.dateTo().isBefore(q.dateFrom()))
            throw bad("截止日期不能早于开始日期");
        return store.entries(
                a.tenantId().toString(),
                category,
                new LogisticsCostQuery(
                        Math.max(0, q.begin()),
                        q.step() <= 0 ? 20 : Math.min(200, q.step()),
                        q.documentNo(),
                        q.title(),
                        q.remark(),
                        q.trackingNo(),
                        q.sourceDocumentNo(),
                        q.typeCode(),
                        q.cityCode(),
                        q.statusCode(),
                        q.paymentStatusCode(),
                        q.dateFrom(),
                        q.dateTo(),
                        q.warehouseId(),
                        q.ownerEmployeeCode(),
                        q.objectName(),
                        q.sourceSystem(),
                        q.payerCode(),
                        q.missingAmount(),
                        q.senderName(),
                        q.receiverName(),
                        q.carrierName()),
                p);
    }

    public LogisticsCostDetailView entry(String value, String id) {
        String c = category(value), p = permission(c, "read");
        return store.entry(actor(p).tenantId().toString(), c, id, p);
    }

    public List<com.rigour.order.api.v1.model.LogisticsCostEmployeeView> employees() {
        String action = permission("LOGISTICS", "read");
        actor(action);
        return dictionary.employees(action);
    }

    private String responsibleEmployee(LogisticsCostCommand command, String action) {
        if (command.ownerEmployeeCode() == null || command.ownerEmployeeCode().isBlank()) {
            if (command.departmentId() != null) throw bad("请先选择费用负责人，不接受未经核验的部门或员工");
            return null;
        }
        text(command.ownerEmployeeCode(), 50, "费用负责人员工编号");
        var employee =
                dictionary.employees(action).stream()
                        .filter(e -> e.employeeCode().equals(command.ownerEmployeeCode()))
                        .findFirst()
                        .orElseThrow(() -> bad("费用负责人不存在或无权限"));
        if (!Objects.equals(employee.departmentId(), command.departmentId()))
            throw bad("负责人部门与员工不一致，请重新选择");
        return employee.employeeName();
    }

    public List<LogisticsCostAuditView> history(String value, String id) {
        var row = entry(value, id);
        return store.history(AuthorizationContext.requireCurrent().tenantId().toString(), row.id());
    }

    public List<LogisticsCostSourceView> sources(String leg, String keyword) {
        String p = permission("LOGISTICS", "read"), t = actor(p).tenantId().toString();
        leg(leg);
        return store.sources(t, leg, keyword, List.of(), p);
    }

    public LogisticsCostDetailView save(String value, String id, LogisticsCostCommand command) {
        return operation(
                id == null ? "CREATE" : "UPDATE", id, () -> saveValidated(value, id, command));
    }

    private LogisticsCostDetailView saveValidated(String value, String id, LogisticsCostCommand c) {
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
        rules(() -> LogisticsCostRules.money(c.amount(), false));
        if (c.paymentStatusCode() != null && !c.paymentStatusCode().isBlank())
            valid(t, "COST_PAYMENT_STATUS", c.paymentStatusCode());
        if ("PAID".equals(c.paymentStatusCode()) && c.amount() == null) throw bad("已付款费用必须填写金额");
        if (id == null) {
            try {
                UUID.fromString(c.requestId());
            } catch (Exception e) {
                throw bad("requestId格式无效");
            }
        } else if (c.revision() == null || c.revision() < 1) throw bad("缺少费用版本，请刷新");
        if (!Objects.equals(dictionary.city(c.cityCode(), p), c.cityName()))
            throw bad("城市名称与授权目录不一致，请重新选择");
        List<LogisticsCostSourceView> sources =
                new LogisticsCostValidator(store, dictionary).validate(t, c, p, false);
        String warehouseName = null, employeeName = responsibleEmployee(c, p);
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

    public LogisticsCostDetailView action(
            String value, String id, LogisticsCostActionCommand command, boolean confirm) {
        return operation(
                confirm ? "CONFIRM" : "VOID",
                id,
                () -> actionValidated(value, id, command, confirm));
    }

    private LogisticsCostDetailView actionValidated(
            String value, String id, LogisticsCostActionCommand c, boolean confirm) {
        String category = category(value), p = permission(category, confirm ? "confirm" : "void");
        var a = actor(p);
        if (c == null || c.revision() < 1) throw bad("缺少费用版本");
        text(c.reason(), 500, "操作原因");
        if (confirm) {
            var current = store.entry(a.tenantId().toString(), category, id, p);
            dictionary.city(current.data().cityCode(), p);
            responsibleEmployee(current.data(), p);
            new LogisticsCostValidator(store, dictionary)
                    .validate(a.tenantId().toString(), current.data(), p, true);
        }
        return store.transition(
                a.tenantId().toString(), category, id, c, a.principalId().toString(), p);
    }

    private void valid(String tenant, String code, String value) {
        if (value == null || !dictionary.validCodes(tenant, code).contains(value))
            throw bad("请选择启用的" + code + "字典项");
    }

    private static void leg(String value) {
        if (!Set.of("PURCHASE", "TRANSFER", "DELIVERY").contains(value == null ? "" : value))
            throw bad("未知物流类型");
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

    private LogisticsCostDetailView operation(
            String operation,
            String id,
            java.util.function.Supplier<LogisticsCostDetailView> work) {
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

    public List<LogisticsCostSourceView> sources(
            String leg, String keyword, String city, int begin) {
        String action = permission("LOGISTICS", "read");
        String tenant = actor(action).tenantId().toString();
        leg(leg);
        text(keyword, 200, "搜索关键字");
        text(city, 64, "城市");
        if (begin < 0 || begin > 100000) throw bad("查询位置无效");
        if (city != null && !city.isBlank()) dictionary.city(city, action);
        return store.sources(tenant, leg, keyword, List.of(), action, city, begin);
    }
}
