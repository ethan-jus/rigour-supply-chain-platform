package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** 新版整单费用协议。业务引用使用编码，sources独立持久化至sources_json。 */
public record LogisticsCostLedger(
        int schemaVersion,
        String transportPurposeCode,
        String deliveryModeCode,
        String carrierCode,
        String costMonth,
        String trackingNo,
        String transportMethodCode,
        Party sender,
        List<Party> receivingPoints,
        List<Fee> feeItems,
        List<Source> sources,
        String nonSalesPurposeCode,
        String noTrackingReason,
        String noSourceReason,
        List<Long> scopeWarehouseIds,
        List<String> scopeCityCodes,
        SourceSync sourceSync,
        String driverName,
        String vehicleNo) {
    public LogisticsCostLedger(
            int schemaVersion,
            String transportPurposeCode,
            String deliveryModeCode,
            String carrierCode,
            String costMonth,
            String trackingNo,
            String transportMethodCode,
            Party sender,
            List<Party> receivingPoints,
            List<Fee> feeItems,
            List<Source> sources,
            String nonSalesPurposeCode,
            String noTrackingReason,
            String noSourceReason,
            List<Long> scopeWarehouseIds,
            List<String> scopeCityCodes,
            SourceSync sourceSync) {
        this(
                schemaVersion,
                transportPurposeCode,
                deliveryModeCode,
                carrierCode,
                costMonth,
                trackingNo,
                transportMethodCode,
                sender,
                receivingPoints,
                feeItems,
                sources,
                nonSalesPurposeCode,
                noTrackingReason,
                noSourceReason,
                scopeWarehouseIds,
                scopeCityCodes,
                sourceSync,
                null,
                null);
    }

    public record SourceSync(
            String originBizKey,
            long originVersion,
            String lastAppliedFingerprint,
            boolean manuallyChanged,
            long lastSeenVersion,
            String lastSeenFingerprint,
            boolean differencePending) {}

    public LogisticsCostLedger(
            int schemaVersion,
            String transportPurposeCode,
            String deliveryModeCode,
            String carrierCode,
            String costMonth,
            String trackingNo,
            String transportMethodCode,
            Party sender,
            List<Party> receivingPoints,
            List<Fee> feeItems,
            List<Source> sources,
            String nonSalesPurposeCode,
            String noTrackingReason,
            String noSourceReason,
            List<Long> scopeWarehouseIds,
            List<String> scopeCityCodes) {
        this(
                schemaVersion,
                transportPurposeCode,
                deliveryModeCode,
                carrierCode,
                costMonth,
                trackingNo,
                transportMethodCode,
                sender,
                receivingPoints,
                feeItems,
                sources,
                nonSalesPurposeCode,
                noTrackingReason,
                noSourceReason,
                scopeWarehouseIds,
                scopeCityCodes,
                null);
    }

    public record Party(
            String pointCode,
            String partyTypeCode,
            String partyCode,
            String manualName,
            String displayName,
            String contactName,
            String phone,
            String address,
            String cityCode) {}

    public record Fee(
            String feeKey,
            String feeTypeCode,
            BigDecimal feeAmount,
            String bearerCode,
            BigDecimal ourAmount,
            String allocationMethodCode,
            String zeroCostReason,
            String remark,
            String basisUnitCode) {
        public Fee(
                String feeKey,
                String feeTypeCode,
                BigDecimal feeAmount,
                String bearerCode,
                BigDecimal ourAmount,
                String allocationMethodCode,
                String zeroCostReason,
                String remark) {
            this(
                    feeKey,
                    feeTypeCode,
                    feeAmount,
                    bearerCode,
                    ourAmount,
                    allocationMethodCode,
                    zeroCostReason,
                    remark,
                    null);
        }
    }

    public record Allocation(
            String feeKey,
            String receivingPointCode,
            String cityCode,
            BigDecimal basis,
            BigDecimal amount) {}

    public record Source(
            String orderTypeCode,
            String orderCode,
            String linkRoleCode,
            List<Allocation> allocations,
            String noSourceReason,
            String employeeCode,
            String employeeName,
            Long departmentId,
            String departmentName,
            String sourceWarehouseCode) {
        public Source(
                String orderTypeCode,
                String orderCode,
                String linkRoleCode,
                List<Allocation> allocations,
                String noSourceReason) {
            this(
                    orderTypeCode,
                    orderCode,
                    linkRoleCode,
                    allocations,
                    noSourceReason,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public Source attributed(LogisticsCostSourceView source) {
            return new Source(
                    orderTypeCode,
                    orderCode,
                    linkRoleCode,
                    allocations,
                    noSourceReason,
                    source.employeeCode(),
                    source.employeeName(),
                    source.departmentId(),
                    source.departmentName(),
                    source.senderCode());
        }
    }

    public LogisticsCostLedger withSourceSync(SourceSync sync) {
        return new LogisticsCostLedger(
                schemaVersion,
                transportPurposeCode,
                deliveryModeCode,
                carrierCode,
                costMonth,
                trackingNo,
                transportMethodCode,
                sender,
                receivingPoints,
                feeItems,
                sources,
                nonSalesPurposeCode,
                noTrackingReason,
                noSourceReason,
                scopeWarehouseIds,
                scopeCityCodes,
                sync,
                driverName,
                vehicleNo);
    }

    public LogisticsCostLedger withSources(List<Source> value) {
        return new LogisticsCostLedger(
                schemaVersion,
                transportPurposeCode,
                deliveryModeCode,
                carrierCode,
                costMonth,
                trackingNo,
                transportMethodCode,
                sender,
                receivingPoints,
                feeItems,
                value,
                nonSalesPurposeCode,
                noTrackingReason,
                noSourceReason,
                scopeWarehouseIds,
                scopeCityCodes,
                sourceSync,
                driverName,
                vehicleNo);
    }

    public LogisticsCostLedger withTransportType(String value) {
        return new LogisticsCostLedger(
                schemaVersion,
                value,
                deliveryModeCode,
                carrierCode,
                costMonth,
                trackingNo,
                transportMethodCode,
                sender,
                receivingPoints,
                feeItems,
                sources,
                nonSalesPurposeCode,
                noTrackingReason,
                noSourceReason,
                scopeWarehouseIds,
                scopeCityCodes,
                sourceSync,
                driverName,
                vehicleNo);
    }

    public LogisticsCostLedger resolved(Party from, List<Party> points, List<Long> warehouses) {
        return new LogisticsCostLedger(
                schemaVersion,
                transportPurposeCode,
                deliveryModeCode,
                carrierCode,
                costMonth,
                trackingNo,
                transportMethodCode,
                from,
                points,
                feeItems,
                sources,
                nonSalesPurposeCode,
                noTrackingReason,
                noSourceReason,
                warehouses,
                scopeCityCodes,
                sourceSync,
                driverName,
                vehicleNo);
    }

    public LogisticsCostLedger withScopeCities(List<String> cities) {
        return new LogisticsCostLedger(
                schemaVersion,
                transportPurposeCode,
                deliveryModeCode,
                carrierCode,
                costMonth,
                trackingNo,
                transportMethodCode,
                sender,
                receivingPoints,
                feeItems,
                sources,
                nonSalesPurposeCode,
                noTrackingReason,
                noSourceReason,
                scopeWarehouseIds,
                cities,
                sourceSync,
                driverName,
                vehicleNo);
    }
}
