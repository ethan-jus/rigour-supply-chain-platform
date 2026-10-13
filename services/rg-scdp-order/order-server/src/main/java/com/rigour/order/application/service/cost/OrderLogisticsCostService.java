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
        String category = category(value), p = permission(category, "read");
        var a = actor(p);
        if (q.dateFrom() != null && q.dateTo() != null && q.dateTo().isBefore(q.dateFrom()))
            throw bad("截止日期不能早于开始日期");
        return store.entries(
                a.tenantId().toString(),
                category,
                new LogisticsCostQuery(
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

    public LogisticsCostDetailView entry(String value, String id) {
        String c = category(value), p = permission(c, "read");
        return store.entry(actor(p).tenantId().toString(), c, id, p);
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

    public LogisticsCostDetailView save(String value, String id, LogisticsCostCommand c) {
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
        valid(t, "COST_PAYMENT_STATUS", c.paymentStatusCode());
        if ("PAID".equals(c.paymentStatusCode()) && c.amount() == null) throw bad("已付款费用必须填写金额");
        if (id == null) {
            try {
                UUID.fromString(c.requestId());
            } catch (Exception e) {
                throw bad("requestId格式无效");
            }
        } else if (c.revision() == null || c.revision() < 1) throw bad("缺少费用版本，请刷新");
        List<LogisticsCostSourceView> sources = List.of();
        String warehouseName = null, employeeName = null;
        if ("LOGISTICS".equals(category)) {
            if (c.logistics() == null) throw bad("物流参数无效");
            var l = c.logistics();
            leg(l.legCode());
            if (l.sourceIds() == null
                    || l.sourceIds().isEmpty()
                    || l.sourceIds().size() > 100
                    || l.sourceIds().stream().anyMatch(Objects::isNull)) throw bad("请选择1至100张来源单据");
            if (l.sourceIds().stream().distinct().count() != l.sourceIds().size())
                throw bad("来源单据不能重复");
            // 凭证归属随来源接口校验；一期来源单据即运输依据，不接收未核验的凭证编号。
            if (l.voucherIds() != null && !l.voucherIds().isEmpty()) throw bad("请从来源单据详情查看入出库凭证");
            valid(t, "COST_PAYER", l.payerCode());
            valid(t, "COST_TRANSPORT_METHOD", l.transportMethodCode());
            text(l.carrierName(), 120, "承运方");
            text(l.trackingNo(), 100, "运单号");
            sources = store.sources(t, l.legCode(), "", l.sourceIds(), p);
            if (sources.size() != l.sourceIds().size()) throw bad("来源单据不存在、未生效或无访问权限");
            var keys =
                    sources.stream()
                            .map(
                                    s ->
                                            s.cityCode()
                                                    + "|"
                                                    + s.warehouseId()
                                                    + "|"
                                                    + s.targetWarehouseId()
                                                    + "|"
                                                    + ("DELIVERY".equals(l.legCode())
                                                            ? ""
                                                            : s.senderCode()))
                            .toList();
            rules(() -> LogisticsCostRules.sameTrip(keys));
            if (sources.stream().anyMatch(s -> !Objects.equals(s.cityCode(), c.cityCode())))
                throw bad("城市必须与来源单据的归属地区一致");
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

    public LogisticsCostDetailView action(
            String value, String id, LogisticsCostActionCommand c, boolean confirm) {
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

    public static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
