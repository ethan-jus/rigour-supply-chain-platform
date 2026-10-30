package com.rigour.order.application.service.cost;

import static com.rigour.order.application.service.cost.OrderLogisticsCostService.bad;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.OrderLogisticsCostStore;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.order.domain.cost.LogisticsCostRules;

import java.util.*;

/** 校验运输对象与来源证据；不创建采购、销售或库存业务。 */
final class LogisticsCostValidator {
    private final OrderLogisticsCostStore store;
    private final OrderLogisticsReferences references;

    LogisticsCostValidator(OrderLogisticsCostStore store, OrderLogisticsReferences references) {
        this.store = store;
        this.references = references;
    }

    List<LogisticsCostSourceView> validate(
            String tenant, LogisticsCostCommand command, String action, boolean confirm) {
        var route = command.logistics();
        if (route == null) throw bad("请填写运输信息");
        if (!Set.of("PURCHASE", "TRANSFER", "DELIVERY")
                .contains(Objects.toString(route.legCode(), ""))) throw bad("请选择运输环节");
        String mode = route.entryMode() == null ? "LINKED" : route.entryMode();
        if (!Set.of("LINKED", "MANUAL", "MIXED", "NONE").contains(mode)) throw bad("录入模式无效");
        var ids = route.sourceIds() == null ? List.<Long>of() : route.sourceIds();
        var documents =
                route.externalDocuments() == null
                        ? List.<LogisticsCostExternalDocument>of()
                        : route.externalDocuments();
        if (ids.size() + documents.stream().filter(d -> d == null || d.linkedId() == null).count()
                        > 100
                || ids.stream().anyMatch(Objects::isNull)
                || ids.stream().distinct().count() != ids.size()) throw bad("同趟最多100张不同来源单据");
        if (Set.of("LINKED", "MIXED").contains(mode) && ids.isEmpty()) throw bad("请选择来源单据");
        if (Set.of("MANUAL", "NONE").contains(mode) && !ids.isEmpty())
            throw bad("手工录入不能携带未声明关联的系统单据");
        if (Set.of("MANUAL", "MIXED").contains(mode) && documents.isEmpty()) throw bad("请填写外部来源单据");
        if ("NONE".equals(mode) && !documents.isEmpty()) throw bad("无来源单据模式不能携带外部单据");
        if ("LINKED".equals(mode)
                && documents.stream().anyMatch(d -> d == null || d.linkedId() == null))
            throw bad("未关联的外部单据请选择混合来源模式");
        if (route.voucherIds() != null && !route.voucherIds().isEmpty())
            throw bad("请从来源单据详情查看入出库凭证");
        optional(route.carrierName(), 120, "承运方");
        if (confirm) required(route.carrierName(), 120, "承运方");
        optional(route.trackingNo(), 100, "运单号");
        optional(route.noSourceReason(), 500, "无单原因");
        optional(route.manualPartyReason(), 500, "手填对象说明");
        dictionary(tenant, "COST_PAYER", route.payerCode(), confirm);
        dictionary(tenant, "COST_TRANSPORT_METHOD", route.transportMethodCode(), false);
        if (confirm && "NONE".equals(mode)) required(route.noSourceReason(), 500, "无来源单据原因");
        List<LogisticsCostSourceView> sources =
                ids.isEmpty() ? List.of() : store.sources(tenant, route.legCode(), "", ids, action);
        if (sources.size() != ids.size()) throw bad("来源单据不存在、未生效或无访问权限");
        boolean supplementWarehouse =
                "DELIVERY".equals(route.legCode())
                        && sources.stream().anyMatch(s -> s.warehouseId() == null);
        if (supplementWarehouse) {
            if (route.sourceWarehouseId() == null) throw bad("销售订单未关联仓库，请补充本次实际发货城市仓");
            warehouse(
                    route.sourceWarehouseId(),
                    route.senderName(),
                    "CITY",
                    command.cityCode(),
                    action,
                    confirm && !present(route.manualPartyReason()));
            if (sources.stream()
                    .anyMatch(
                            s ->
                                    s.warehouseId() != null
                                            && !s.warehouseId().equals(route.sourceWarehouseId())))
                throw bad("补充城市仓与已关联订单仓库不一致，不能跨仓合运");
        }
        if (!sources.isEmpty()) {
            try {
                LogisticsCostRules.sameTrip(
                        sources.stream()
                                .map(
                                        s ->
                                                s.cityCode()
                                                        + "|"
                                                        + (s.warehouseId() == null
                                                                        && supplementWarehouse
                                                                ? route.sourceWarehouseId()
                                                                : s.warehouseId())
                                                        + "|"
                                                        + ("DELIVERY".equals(route.legCode())
                                                                ? ""
                                                                : s.targetWarehouseId())
                                                        + "|"
                                                        + ("DELIVERY".equals(route.legCode())
                                                                ? ""
                                                                : s.senderCode()))
                                .toList());
            } catch (IllegalArgumentException e) {
                throw bad(e.getMessage());
            }
            if (sources.stream().anyMatch(s -> !Objects.equals(s.cityCode(), command.cityCode())))
                throw bad("城市必须与来源单据一致，不能跨城市合运");
            for (var source : sources) {
                if ("PURCHASE".equals(route.legCode()))
                    type(
                            source.targetWarehouseTypeCode(),
                            "CENTER",
                            confirm && !present(route.manualPartyReason()));
                else if ("TRANSFER".equals(route.legCode())) {
                    type(
                            source.warehouseTypeCode(),
                            "CENTER",
                            confirm && !present(route.manualPartyReason()));
                    type(
                            source.targetWarehouseTypeCode(),
                            "CITY",
                            confirm && !present(route.manualPartyReason()));
                } else if (source.warehouseId() != null)
                    type(
                            source.warehouseTypeCode(),
                            "CITY",
                            confirm && !present(route.manualPartyReason()));
            }
        }
        boolean manual = !"LINKED".equals(mode);
        if (manual) validateParties(command, action, confirm);
        else if (route.supplierId() != null
                || route.customerId() != null
                || route.sourceWarehouseId() != null && !supplementWarehouse
                || route.targetWarehouseId() != null) throw bad("系统关联模式的运输对象由来源单据确定，请清除手填对象引用");
        Set<String> unique = new HashSet<>();
        Set<Long> linked = new HashSet<>();
        for (var doc : documents) {
            if (doc == null) throw bad("外部来源单据不能为空");
            if (!Set.of("DHB", "MANUAL", "OTHER")
                    .contains(Objects.toString(doc.sourceSystem(), ""))) throw bad("请选择原始来源系统");
            if (!route.legCode().equals(doc.documentType())) throw bad("外部单据类型与运输环节不一致");
            required(doc.documentNo(), 100, "原始单号");
            if ("OTHER".equals(doc.sourceSystem())) required(doc.sourceSystemName(), 120, "其他系统名称");
            if (doc.documentDate() == null) throw bad("请选择外部单据日期");
            required(doc.senderName(), 200, "单据发货方");
            required(doc.receiverName(), 200, "单据收货方");
            optional(doc.remark(), 500, "来源备注");
            optional(doc.differenceReason(), 500, "关联差异说明");
            optional(doc.originAddress(), 500, "单据发货地址");
            optional(doc.destinationAddress(), 500, "单据收货地址");
            if (!unique.add(doc.sourceSystem() + "|" + doc.documentNo().trim()))
                throw bad("外部单据不能重复");
            if (doc.linkedId() != null) {
                if (!linked.add(doc.linkedId())) throw bad("同一系统单据不能重复补关联");
                var source =
                        sources.stream()
                                .filter(s -> doc.linkedId().equals(s.id()))
                                .findFirst()
                                .orElseThrow(() -> bad("补关联的系统单据必须选入本趟来源"));
                if ((!Objects.equals(doc.senderName(), source.senderName())
                                || !Objects.equals(doc.receiverName(), source.receiverName())
                                || !Objects.equals(
                                        doc.documentNo(),
                                        present(source.originalNo())
                                                ? source.originalNo()
                                                : source.documentNo()))
                        && !present(doc.differenceReason())) throw bad("补关联单据的双方或原始单号有差异，请填写差异说明");
            } else {
                if (!Objects.equals(doc.senderName(), route.senderName())
                        || !"DELIVERY".equals(route.legCode())
                                && !Objects.equals(doc.receiverName(), route.receiverName()))
                    throw bad("外部单据与本趟发收方不一致");
                if (confirm && "DELIVERY".equals(route.legCode()) && documents.size() > 1)
                    required(doc.destinationAddress(), 500, "每张配送单的收货地址");
            }
        }
        if ("MIXED".equals(mode)) {
            var first = sources.getFirst();
            if (!(supplementWarehouse && first.warehouseId() == null)
                            && !Objects.equals(first.senderName(), route.senderName())
                    || !"DELIVERY".equals(route.legCode())
                            && !Objects.equals(first.receiverName(), route.receiverName()))
                throw bad("混合来源必须属于同一发货方和收货仓");
            if ("PURCHASE".equals(route.legCode())
                            && !Objects.equals(first.targetWarehouseId(), route.targetWarehouseId())
                    || !"PURCHASE".equals(route.legCode())
                            && first.warehouseId() != null
                            && !Objects.equals(first.warehouseId(), route.sourceWarehouseId()))
                throw bad("混合来源必须使用相同仓库");
        }
        validateStops(route, sources, confirm);
        return sources;
    }

    private void validateParties(LogisticsCostCommand c, String action, boolean confirm) {
        var l = c.logistics();
        switch (l.legCode()) {
            case "PURCHASE" -> {
                if (l.customerId() != null || l.sourceWarehouseId() != null)
                    throw bad("供应商到中心仓只能关联供应商和收货中心仓");
            }
            case "TRANSFER" -> {
                if (l.supplierId() != null || l.customerId() != null)
                    throw bad("中心仓到城市仓只能关联发货中心仓和收货城市仓");
            }
            case "DELIVERY" -> {
                if (l.supplierId() != null || l.targetWarehouseId() != null)
                    throw bad("城市仓到客户只能关联发货城市仓和客户");
            }
        }
        required(l.senderName(), 200, "发货方");
        required(l.receiverName(), 200, "收货方");
        optional(l.originAddress(), 500, "发货地址");
        optional(l.destinationAddress(), 500, "收货地址");
        if (confirm
                && "DELIVERY".equals(l.legCode())
                && (l.externalDocuments() == null || l.externalDocuments().size() <= 1))
            required(l.destinationAddress(), 500, "客户收货地址");
        if (l.supplierId() != null
                && !Objects.equals(
                        references.party("suppliers", l.supplierId(), c.cityCode(), action),
                        l.senderName())) throw bad("发货方与所选供应商不一致");
        if (l.customerId() != null
                && !Objects.equals(
                        references.party("customers", l.customerId(), c.cityCode(), action),
                        l.receiverName())) throw bad("收货方与所选客户不一致");
        if (l.sourceWarehouseId() != null)
            warehouse(
                    l.sourceWarehouseId(),
                    l.senderName(),
                    "TRANSFER".equals(l.legCode()) ? "CENTER" : "CITY",
                    c.cityCode(),
                    action,
                    confirm && !present(l.manualPartyReason()));
        if (l.targetWarehouseId() != null)
            warehouse(
                    l.targetWarehouseId(),
                    l.receiverName(),
                    "PURCHASE".equals(l.legCode()) ? "CENTER" : "CITY",
                    c.cityCode(),
                    action,
                    confirm && !present(l.manualPartyReason()));
        if (Objects.equals(l.sourceWarehouseId(), l.targetWarehouseId())
                && l.sourceWarehouseId() != null) throw bad("发货仓与收货仓不能相同");
        if (confirm
                && (("PURCHASE".equals(l.legCode())
                                && (l.supplierId() == null || l.targetWarehouseId() == null))
                        || ("TRANSFER".equals(l.legCode())
                                && (l.sourceWarehouseId() == null || l.targetWarehouseId() == null))
                        || ("DELIVERY".equals(l.legCode()) && l.sourceWarehouseId() == null)))
            required(l.manualPartyReason(), 500, "未关联主档的对象说明");
    }

    private void warehouse(
            Long id, String name, String expected, String city, String action, boolean confirm) {
        var warehouse = references.warehouseDetail(id, action);
        if (warehouse == null || !Objects.equals(warehouse.name(), name)) throw bad("发收方与所选仓库不一致");
        type(warehouse.warehouseTypeCode(), expected, confirm);
        if ("CITY".equals(expected) && !Objects.equals(warehouse.regionCode(), city))
            throw bad("城市仓与归属城市不一致");
    }

    private static void type(String actual, String expected, boolean confirm) {
        if (!present(actual)) {
            if (confirm) throw bad("来源仓库类型待核对，请维护主档后确认");
            return;
        }
        if (!expected.equals(actual))
            throw bad("仓库类型与运输环节不符，需要" + ("CENTER".equals(expected) ? "中心仓" : "城市仓"));
    }

    private static void validateStops(
            LogisticsCostTransportDetails l,
            List<LogisticsCostSourceView> sources,
            boolean confirm) {
        var stops =
                l.deliveryStops() == null
                        ? List.<LogisticsCostDeliveryStop>of()
                        : l.deliveryStops();
        if (!"DELIVERY".equals(l.legCode()) && !stops.isEmpty()) throw bad("非配送运输不能填写客户收货点");
        Set<Long> ids = new HashSet<>();
        for (var stop : stops) {
            if (stop == null || !ids.add(stop.sourceId())) throw bad("客户收货点不能重复或为空");
            var source =
                    sources.stream()
                            .filter(s -> s.id().equals(stop.sourceId()))
                            .findFirst()
                            .orElseThrow(() -> bad("收货点缺少对应销售订单"));
            if (!Objects.equals(stop.customerName(), source.receiverName()))
                throw bad("收货客户与销售订单不一致");
            optional(stop.address(), 500, "收货地址");
        }
        if (confirm && "DELIVERY".equals(l.legCode()))
            for (var source : sources)
                if (!present(source.destinationAddress())
                        && stops.stream()
                                .noneMatch(
                                        s ->
                                                s.sourceId().equals(source.id())
                                                        && present(s.address())))
                    throw bad("请补齐每张销售订单的实际收货地址");
    }

    private void dictionary(String tenant, String code, String value, boolean required) {
        if (!present(value)) {
            if (required) throw bad("请选择费用承担方");
            return;
        }
        if (!references.validCodes(tenant, code).contains(value)) throw bad("请选择启用的字典项");
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static void required(String s, int max, String label) {
        if (!present(s)) throw bad("请填写" + label);
        optional(s, max, label);
    }

    private static void optional(String s, int max, String label) {
        if (s != null && s.length() > max) throw bad(label + "长度不能超过" + max);
    }
}
