package com.rigour.order.api.v1.model;

import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class TransportRecordModels {
    private TransportRecordModels() {}

    public record Draft(
            Integer revision,
            String transportType,
            LocalDate date,
            String carrierCode,
            String trackingNo,
            String platformOrderNo,
            String warehouseCode,
            String senderName,
            String senderContact,
            String senderPhone,
            String senderAddress,
            String cityCode,
            BigDecimal amount,
            String ownerEmployeeCode,
            String remark,
            String reason,
            String zeroReason,
            List<String> orderCodes,
            String requestId,
            String paymentStatusCode,
            List<LogisticsCostLedger.ManualBillItem> manualBillItems,
            String deliveryModeCode,
            List<LogisticsCostLedger.Party> receivingPoints,
            Map<String, BigDecimal> orderAllocations,
            java.time.LocalDateTime shippingTime,
            String receivingMethodCode) {
        public Draft(
                Integer revision,
                String transportType,
                LocalDate date,
                String carrierCode,
                String trackingNo,
                String platformOrderNo,
                String warehouseCode,
                String senderName,
                String senderContact,
                String senderPhone,
                String senderAddress,
                String cityCode,
                BigDecimal amount,
                String ownerEmployeeCode,
                String remark,
                String reason,
                String zeroReason,
                List<String> orderCodes,
                String requestId,
                String paymentStatusCode,
                List<LogisticsCostLedger.ManualBillItem> manualBillItems,
                String deliveryModeCode,
                List<LogisticsCostLedger.Party> receivingPoints,
                Map<String, BigDecimal> orderAllocations) {
            this(
                    revision,
                    transportType,
                    date,
                    carrierCode,
                    trackingNo,
                    platformOrderNo,
                    warehouseCode,
                    senderName,
                    senderContact,
                    senderPhone,
                    senderAddress,
                    cityCode,
                    amount,
                    ownerEmployeeCode,
                    remark,
                    reason,
                    zeroReason,
                    orderCodes,
                    requestId,
                    paymentStatusCode,
                    manualBillItems,
                    deliveryModeCode,
                    receivingPoints,
                    orderAllocations,
                    null,
                    null);
        }

        public Draft(
                Integer revision,
                String transportType,
                LocalDate date,
                String carrierCode,
                String trackingNo,
                String platformOrderNo,
                String warehouseCode,
                String senderName,
                String senderContact,
                String senderPhone,
                String senderAddress,
                String cityCode,
                BigDecimal amount,
                String ownerEmployeeCode,
                String remark,
                String reason,
                String zeroReason,
                List<String> orderCodes,
                String requestId,
                String paymentStatusCode,
                List<LogisticsCostLedger.ManualBillItem> manualBillItems,
                String deliveryModeCode,
                List<LogisticsCostLedger.Party> receivingPoints) {
            this(
                    revision,
                    transportType,
                    date,
                    carrierCode,
                    trackingNo,
                    platformOrderNo,
                    warehouseCode,
                    senderName,
                    senderContact,
                    senderPhone,
                    senderAddress,
                    cityCode,
                    amount,
                    ownerEmployeeCode,
                    remark,
                    reason,
                    zeroReason,
                    orderCodes,
                    requestId,
                    paymentStatusCode,
                    manualBillItems,
                    deliveryModeCode,
                    receivingPoints,
                    null);
        }

        public Draft withAllocations(Map<String, BigDecimal> allocations) {
            return new Draft(
                    revision,
                    transportType,
                    date,
                    carrierCode,
                    trackingNo,
                    platformOrderNo,
                    warehouseCode,
                    senderName,
                    senderContact,
                    senderPhone,
                    senderAddress,
                    cityCode,
                    amount,
                    ownerEmployeeCode,
                    remark,
                    reason,
                    zeroReason,
                    orderCodes,
                    requestId,
                    paymentStatusCode,
                    manualBillItems,
                    deliveryModeCode,
                    receivingPoints,
                    allocations,
                    shippingTime,
                    receivingMethodCode);
        }

        public Draft(
                Integer revision,
                String transportType,
                LocalDate date,
                String carrierCode,
                String trackingNo,
                String platformOrderNo,
                String warehouseCode,
                String senderName,
                String senderContact,
                String senderPhone,
                String senderAddress,
                String cityCode,
                BigDecimal amount,
                String ownerEmployeeCode,
                String remark,
                String reason,
                String zeroReason,
                List<String> orderCodes,
                String requestId,
                String paymentStatusCode,
                List<LogisticsCostLedger.ManualBillItem> manualBillItems) {
            this(
                    revision,
                    transportType,
                    date,
                    carrierCode,
                    trackingNo,
                    platformOrderNo,
                    warehouseCode,
                    senderName,
                    senderContact,
                    senderPhone,
                    senderAddress,
                    cityCode,
                    amount,
                    ownerEmployeeCode,
                    remark,
                    reason,
                    zeroReason,
                    orderCodes,
                    requestId,
                    paymentStatusCode,
                    manualBillItems,
                    null,
                    null);
        }

        public Draft {
            orderAllocations =
                    orderAllocations == null
                            ? null
                            : Collections.unmodifiableMap(new LinkedHashMap<>(orderAllocations));
            trackingNo = trackingNo == null || trackingNo.isBlank() ? null : trackingNo.strip();
            platformOrderNo =
                    platformOrderNo == null || platformOrderNo.isBlank()
                            ? null
                            : platformOrderNo.strip();
        }
    }

    public record PlatformRecord(Row source, Draft draft, List<CarrierOrder> billHistory) {}

    public record Page(
            LogisticsCostPageView page,
            Map<String, PlatformRecord> platformRecords,
            Map<String, List<ShipmentTransportModels.Source>> shipmentRecords) {
        public Page(LogisticsCostPageView page, Map<String, PlatformRecord> platformRecords) {
            this(page, platformRecords, Map.of());
        }
    }

    public record Detail(
            LogisticsCostDetailView record,
            PlatformRecord platform,
            List<ShipmentTransportModels.Source> shipments,
            Map<String, BigDecimal> orderAllocations) {
        public Detail(
                LogisticsCostDetailView record,
                PlatformRecord platform,
                List<ShipmentTransportModels.Source> shipments) {
            this(record, platform, shipments, Map.of());
        }

        public Detail(LogisticsCostDetailView record, PlatformRecord platform) {
            this(record, platform, List.of());
        }
    }
}
