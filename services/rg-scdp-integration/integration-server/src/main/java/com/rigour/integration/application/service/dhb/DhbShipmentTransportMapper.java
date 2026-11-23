package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DhbClient.Shipment;
import com.rigour.order.api.v1.model.*;

import java.time.Instant;
import java.util.*;

/** 发货详情保持原文；只提取明确字段，不把商品金额推断为运费。 */
final class DhbShipmentTransportMapper {
    private static final Set<String> SELF_CARRIER_NAMES =
            Set.of("自营", "自营配送", "自配", "自配送", "自有车辆");

    private DhbShipmentTransportMapper() {}

    record ShippingContext(
            Long salesOrderId,
            Long warehouseId,
            String customerCode,
            String cityCode,
            Instant shipTime,
            String carrierName) {}

    static ShipmentTransportModels.Command command(
            UUID connector,
            String sourceNo,
            Shipment summary,
            ShippingContext shipment,
            Map<String, Object> payload,
            String carrier,
            String mode) {
        var detail = map(payload.get("detail"));
        var list = map(payload.get("list"));
        var address = map(detail.get("address"));
        var tracking = trackingNumbers(payload, summary);
        var receiver =
                new LogisticsCostLedger.Party(
                        "TO0",
                        "CUSTOMER",
                        shipment.customerCode(),
                        first(
                                address,
                                "consignee",
                                "company",
                                "company_name",
                                "client_name",
                                "name"),
                        first(
                                address,
                                "consignee",
                                "company",
                                "company_name",
                                "client_name",
                                "name"),
                        first(address, "consignee_contact", "contact", "contact_name", "linkman"),
                        first(address, "consignee_phone", "mobile", "phone", "telephone"),
                        first(address, "consignee_address", "address", "detail_address"),
                        shipment.cityCode());
        String status = first(detail, "status");
        if (status == null) status = summary == null ? first(list, "status") : summary.status();
        String stableId = first(detail, "ships_id");
        if (stableId == null)
            stableId = summary == null ? first(list, "ships_id") : summary.sourceId();
        String carrierName = first(detail, "logistics_name");
        if (carrierName == null) carrierName = shipment.carrierName();
        var raw = new LinkedHashMap<String, Object>(payload);
        raw.put(
                "stock_name",
                Objects.toString(
                        first(detail, "stock_name"),
                        Objects.toString(first(list, "stock_name"), "")));
        var goods = new ArrayList<Map<String, Object>>();
        if (detail.get("list") instanceof List<?> rows)
            for (var row : rows) if (row instanceof Map<?, ?>) goods.add(map(row));
        return new ShipmentTransportModels.Command(
                connector,
                stableId == null ? sourceNo : stableId,
                sourceNo,
                shipment.salesOrderId(),
                shipment.warehouseId(),
                status == null ? "unknown" : status.toLowerCase(Locale.ROOT),
                shipment.shipTime(),
                summary == null ? null : summary.updatedAt(),
                carrier,
                carrierName,
                mode,
                rawValue(payload, "distribution_mode_name", "distribution_mode_name"),
                List.copyOf(tracking),
                receiver,
                raw,
                goods);
    }

    static String doorDeliveryMode(String carrierName, String verifiedCarrierCode) {
        boolean self = carrierName != null && SELF_CARRIER_NAMES.contains(carrierName);
        if (self && verifiedCarrierCode != null && !"SELF".equals(verifiedCarrierCode)) return null;
        if (self || "SELF".equals(verifiedCarrierCode)) return "SELF";
        return verifiedCarrierCode == null ? null : "THIRD_PARTY";
    }

    static String rawValue(Map<String, Object> payload, String code, String name) {
        var detail = map(payload.get("detail"));
        var list = map(payload.get("list"));
        String value = first(detail, code);
        if (value == null) value = first(list, code);
        if (value == null) value = first(detail, name);
        if (value == null) value = first(list, name);
        return value;
    }

    static List<String> trackingNumbers(Map<String, Object> payload) {
        return trackingNumbers(payload, null);
    }

    static List<String> trackingNumbers(Map<String, Object> payload, Shipment summary) {
        var result = new LinkedHashSet<String>();
        collectTracking(rawTracking(payload, summary), result);
        return List.copyOf(result);
    }

    static boolean trackingNotProvided(Map<String, Object> payload, Shipment summary) {
        return trackingNotProvided(map(payload.get("detail")).get("express_num"))
                && trackingNotProvided(map(payload.get("list")).get("express_num"))
                && trackingNotProvided(summary == null ? null : summary.trackingNumber());
    }

    static boolean awaitingThirdPartyTracking(Map<String, Object> payload) {
        String carrier = rawValue(payload, "logistics_name", "logistics_name");
        if (carrier != null)
            return !SELF_CARRIER_NAMES.contains(carrier)
                    && !ShipmentTransportModels.Command.immediateDeliveryCarrier(carrier);
        return "快递".equals(rawValue(payload, "distribution_mode_name", "distribution_mode_name"));
    }

    private static boolean trackingNotProvided(Object value) {
        return switch (value) {
            case null -> true;
            case String text -> text.isBlank() || Set.of("未添加", "未填写", "-").contains(text.strip());
            case List<?> values -> values.stream().allMatch(DhbShipmentTransportMapper::trackingNotProvided);
            case Map<?, ?> values -> values.values().stream().allMatch(DhbShipmentTransportMapper::trackingNotProvided);
            default -> false;
        };
    }

    private static Object rawTracking(Map<String, Object> payload, Shipment summary) {
        var detail = map(payload.get("detail"));
        var list = map(payload.get("list"));
        if (detail.containsKey("express_num")) return detail.get("express_num");
        if (list.containsKey("express_num")) return list.get("express_num");
        return summary == null ? null : summary.trackingNumber();
    }

    private static void collectTracking(Object value, Set<String> result) {
        if (value instanceof List<?> values) values.forEach(v -> collectTracking(v, result));
        else if (value instanceof Map<?, ?> values) {
            for (String key : List.of("express_num", "number", "code", "value"))
                if (values.containsKey(key)) {
                    collectTracking(values.get(key), result);
                    return;
                }
        } else if (value instanceof String text && !text.isBlank())
            for (String raw : text.split("[,，;；\\r\\n]+")) {
                String no = raw.strip();
                if (!no.isEmpty() && !Set.of("未添加", "未填写", "-").contains(no)) result.add(no);
            }
        // 数值编号可能已经丢失前导0，保留原文但不作为去重匹配键。
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String first(Map<String, Object> values, String... keys) {
        for (var key : keys) {
            var v = values.get(key);
            if (v instanceof String s && !s.isBlank()) return s.strip();
        }
        return null;
    }
}
