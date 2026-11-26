package com.rigour.order.api.v1.model;

import java.time.Instant;
import java.util.*;

/** 订货宝发货事实不携带或推算运费；未知原始字段保留在来源快照。 */
public final class ShipmentTransportModels {
    private ShipmentTransportModels() {}

    public record Command(
            UUID connectorId,
            String shipmentId,
            String shipmentNo,
            Long salesOrderId,
            Long warehouseId,
            String sourceStatus,
            Instant shipTime,
            Instant sourceUpdatedAt,
            String carrierCode,
            String carrierName,
            String deliveryModeCode,
            String deliveryModeName,
            List<String> trackingNumbers,
            LogisticsCostLedger.Party receiver,
            Map<String, Object> sourceFields,
            List<Map<String, Object>> goods) {
        public Command withCarrierClassification(String carrier, String mode) {
            return new Command(connectorId, shipmentId, shipmentNo, salesOrderId, warehouseId,
                    sourceStatus, shipTime, sourceUpdatedAt, carrier, carrierName, mode,
                    deliveryModeName, trackingNumbers, receiver, sourceFields, goods);
        }

        public static boolean immediateDeliveryCarrier(String name) {
            return "立即送".equals(name);
        }

        public boolean employeeImmediateDelivery() {
            return immediateDeliveryCarrier(carrierName)
                    && "SELF".equals(deliveryModeCode) && carrierCode == null
                    && trackingNumbers.isEmpty();
        }

        public Command {
            trackingNumbers = trackingNumbers == null ? List.of() : List.copyOf(trackingNumbers);
            sourceFields =
                    sourceFields == null
                            ? Map.of()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
            goods = goods == null ? List.of() : List.copyOf(goods);
        }
    }

    /** Only fields supplied by a business user override subsequent source snapshots. */
    public record LogisticsEdit(
            String carrierCode, String carrierName, List<String> trackingNumbers,
            Instant shipTime, String remark) {
        public LogisticsEdit merge(LogisticsEdit changes) {
            return new LogisticsEdit(
                    first(changes.carrierCode, carrierCode),
                    first(changes.carrierName, carrierName),
                    first(changes.trackingNumbers, trackingNumbers),
                    first(changes.shipTime, shipTime),
                    first(changes.remark, remark));
        }

        public Command apply(Command source) {
            boolean self = "SELF".equals(carrierCode);
            Map<String, Object> fields = new LinkedHashMap<>(source.sourceFields());
            if (remark != null) fields.put("shipmentRemark", remark);
            return new Command(
                    source.connectorId(), source.shipmentId(), source.shipmentNo(),
                    source.salesOrderId(), source.warehouseId(), source.sourceStatus(),
                    first(shipTime, source.shipTime()), source.sourceUpdatedAt(),
                    self ? null : first(carrierCode, source.carrierCode()),
                    first(carrierName, source.carrierName()),
                    carrierCode == null ? source.deliveryModeCode() : self ? "SELF" : "THIRD_PARTY",
                    source.deliveryModeName(), first(trackingNumbers, source.trackingNumbers()),
                    source.receiver(), fields, source.goods());
        }

        private static <T> T first(T value, T fallback) {
            return value == null ? fallback : value;
        }
    }

    public record CarrierBackfill(String sourceValue, String carrierCode) {
        public static final int BATCH_SIZE = 100;
    }

    public record Source(
            String id, String costId, Command latest, String issue, boolean differencePending,
            String salesOrderNo) {
        public Source(String id, String costId, Command latest, String issue, boolean differencePending) {
            this(id, costId, latest, issue, differencePending, null);
        }
    }
}
