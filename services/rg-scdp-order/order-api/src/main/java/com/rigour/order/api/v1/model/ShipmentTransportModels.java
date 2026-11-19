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

        public Command {
            trackingNumbers = trackingNumbers == null ? List.of() : List.copyOf(trackingNumbers);
            sourceFields =
                    sourceFields == null
                            ? Map.of()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
            goods = goods == null ? List.of() : List.copyOf(goods);
        }
    }

    public record CarrierBackfill(String sourceValue, String carrierCode) {
        public static final int BATCH_SIZE = 100;
    }

    public record Source(
            String id, String costId, Command latest, String issue, boolean differencePending) {}
}
