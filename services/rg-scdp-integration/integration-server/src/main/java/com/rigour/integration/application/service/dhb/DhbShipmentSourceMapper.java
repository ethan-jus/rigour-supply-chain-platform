package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DhbClient.Shipment;
import com.rigour.order.api.v1.model.SalesShipmentSnapshotCommand;

import java.time.*;
import java.util.*;

/** 官方 shipped=待发货，receivedin=待收货；来源时间分别保留，不拿创建时间充当发货。 */
final class DhbShipmentSourceMapper {
    private DhbShipmentSourceMapper() {}

    static SalesShipmentSnapshotCommand command(
            UUID connector, String no, Shipment summary, Map<String, Object> payload) {
        String status = value(payload, "status");
        if (status == null && summary != null) status = summary.status();
        return new SalesShipmentSnapshotCommand(
                connector,
                no,
                value(payload, "orders_num"),
                value(payload, "client_num"),
                value(payload, "client_name"),
                contact(payload),
                value(payload, "stock_name"),
                status(status),
                value(payload, "logistics_name"),
                String.join(",", DhbShipmentTransportMapper.trackingNumbers(payload, summary)),
                time(value(payload, "ships_date")),
                time(value(payload, "create_date")),
                time(value(payload, "update_date")),
                payload);
    }

    static String status(String raw) {
        return switch (Objects.toString(raw, "").toLowerCase(Locale.ROOT)) {
            case "receivedin" -> "SHIPPED";
            case "received" -> "SIGNED";
            case "cancelled" -> "CANCELLED";
            case "shipped", "" -> "CREATED";
            default -> throw new IllegalArgumentException("订货宝发货状态未识别: " + raw);
        };
    }

    @SuppressWarnings("unchecked")
    static String value(Map<String, Object> payload, String key) {
        var detail =
                payload.get("detail") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.<String, Object>of();
        var list =
                payload.get("list") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.<String, Object>of();
        Object v = detail.containsKey(key) ? detail.get(key) : list.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().strip();
    }

    private static String contact(Map<String, Object> p) {
        if (p.get("detail") instanceof Map<?, ?> d && d.get("address") instanceof Map<?, ?> a) {
            Object v = a.get("consignee_phone");
            if (v != null) return v.toString();
        }
        return null;
    }

    static Instant time(String value) {
        if (value == null || value.startsWith("0000")) return null;
        String s = value.replace(' ', 'T');
        if (s.length() == 10)
            return LocalDate.parse(s).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        try {
            return Instant.parse(s);
        } catch (java.time.format.DateTimeParseException ignored) {
        }
        return LocalDateTime.parse(s).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }
}
