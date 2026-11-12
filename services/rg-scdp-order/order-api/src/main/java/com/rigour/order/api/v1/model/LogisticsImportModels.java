package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 平台来源与人工归属分开保存；同步只生成待确认运输记录。 */
public final class LogisticsImportModels {
    private LogisticsImportModels() {}

    public record CarrierOrder(
            String enterpriseId,
            String orderNo,
            String status,
            String useTime,
            BigDecimal paidAmount,
            Map<String, String> raw,
            List<String> salesOrderNos,
            int line,
            SourceFacts facts) {
        public CarrierOrder(
                String enterpriseId,
                String orderNo,
                String status,
                String useTime,
                BigDecimal paidAmount,
                Map<String, String> raw,
                List<String> salesOrderNos,
                int line) {
            this(
                    enterpriseId,
                    orderNo,
                    status,
                    useTime,
                    paidAmount,
                    raw,
                    salesOrderNos,
                    line,
                    null);
        }
    }

    /** 平台账单与支付结构分别保存，金额均为元；不能将实付金额冒充最终成本。 */
    public record SourceFacts(
            String connectorId,
            String rawLandingId,
            String sourceUserId,
            String sourceStaffId,
            String sourceEmployeeName,
            String mappedEmployeeCode,
            BigDecimal orderAmount,
            BigDecimal billAmount,
            BigDecimal unpaidAmount,
            BigDecimal refundAmount,
            BigDecimal couponAmount,
            BigDecimal bonusAmount,
            String completeTime,
            String billUpdatedTime,
            String billState,
            String issue,
            List<Stop> stops,
            List<Fee> fees,
            List<Voucher> receipts,
            List<Voucher> billVouchers,
            Long employeeMappingRevision,
            String sourceDepartmentName,
            ReceiptInfo receiptInfo,
            List<StopPhotos> stopPhotos) {
        public SourceFacts(
                String connectorId,
                String rawLandingId,
                String sourceUserId,
                String sourceStaffId,
                String sourceEmployeeName,
                String mappedEmployeeCode,
                BigDecimal orderAmount,
                BigDecimal billAmount,
                BigDecimal unpaidAmount,
                BigDecimal refundAmount,
                BigDecimal couponAmount,
                BigDecimal bonusAmount,
                String completeTime,
                String billUpdatedTime,
                String billState,
                String issue,
                List<Stop> stops,
                List<Fee> fees,
                List<Voucher> receipts,
                List<Voucher> billVouchers,
                Long employeeMappingRevision,
                String sourceDepartmentName) {
            this(
                    connectorId,
                    rawLandingId,
                    sourceUserId,
                    sourceStaffId,
                    sourceEmployeeName,
                    mappedEmployeeCode,
                    orderAmount,
                    billAmount,
                    unpaidAmount,
                    refundAmount,
                    couponAmount,
                    bonusAmount,
                    completeTime,
                    billUpdatedTime,
                    billState,
                    issue,
                    stops,
                    fees,
                    receipts,
                    billVouchers,
                    employeeMappingRevision,
                    sourceDepartmentName,
                    null,
                    List.of());
        }

        public SourceFacts(
                String connectorId,
                String rawLandingId,
                String sourceUserId,
                String sourceStaffId,
                String sourceEmployeeName,
                String mappedEmployeeCode,
                BigDecimal orderAmount,
                BigDecimal billAmount,
                BigDecimal unpaidAmount,
                BigDecimal refundAmount,
                BigDecimal couponAmount,
                BigDecimal bonusAmount,
                String completeTime,
                String billUpdatedTime,
                String billState,
                String issue,
                List<Stop> stops,
                List<Fee> fees,
                List<Voucher> receipts,
                List<Voucher> billVouchers,
                Long employeeMappingRevision) {
            this(
                    connectorId,
                    rawLandingId,
                    sourceUserId,
                    sourceStaffId,
                    sourceEmployeeName,
                    mappedEmployeeCode,
                    orderAmount,
                    billAmount,
                    unpaidAmount,
                    refundAmount,
                    couponAmount,
                    bonusAmount,
                    completeTime,
                    billUpdatedTime,
                    billState,
                    issue,
                    stops,
                    fees,
                    receipts,
                    billVouchers,
                    employeeMappingRevision,
                    null);
        }

        public SourceFacts {
            stops = stops == null ? List.of() : List.copyOf(stops);
            fees = fees == null ? List.of() : List.copyOf(fees);
            receipts = receipts == null ? List.of() : List.copyOf(receipts);
            billVouchers = billVouchers == null ? List.of() : List.copyOf(billVouchers);
            stopPhotos = stopPhotos == null ? List.of() : List.copyOf(stopPhotos);
            employeeMappingRevision =
                    employeeMappingRevision == null ? 0L : employeeMappingRevision;
        }

        public SourceFacts(
                String connectorId,
                String rawLandingId,
                String sourceUserId,
                String sourceStaffId,
                String sourceEmployeeName,
                String mappedEmployeeCode,
                BigDecimal orderAmount,
                BigDecimal billAmount,
                BigDecimal unpaidAmount,
                BigDecimal refundAmount,
                BigDecimal couponAmount,
                BigDecimal bonusAmount,
                String completeTime,
                String billUpdatedTime,
                String billState,
                String issue,
                List<Stop> stops,
                List<Fee> fees,
                List<Voucher> receipts,
                List<Voucher> billVouchers) {
            this(
                    connectorId,
                    rawLandingId,
                    sourceUserId,
                    sourceStaffId,
                    sourceEmployeeName,
                    mappedEmployeeCode,
                    orderAmount,
                    billAmount,
                    unpaidAmount,
                    refundAmount,
                    couponAmount,
                    bonusAmount,
                    completeTime,
                    billUpdatedTime,
                    billState,
                    issue,
                    stops,
                    fees,
                    receipts,
                    billVouchers,
                    0L);
        }

        public SourceFacts(
                String connectorId,
                String rawLandingId,
                String sourceUserId,
                String sourceStaffId,
                String sourceEmployeeName,
                String mappedEmployeeCode,
                BigDecimal orderAmount,
                BigDecimal billAmount,
                BigDecimal unpaidAmount,
                BigDecimal refundAmount,
                BigDecimal couponAmount,
                BigDecimal bonusAmount,
                String completeTime,
                String billUpdatedTime,
                String billState,
                String issue,
                List<Stop> stops,
                List<Fee> fees) {
            this(
                    connectorId,
                    rawLandingId,
                    sourceUserId,
                    sourceStaffId,
                    sourceEmployeeName,
                    mappedEmployeeCode,
                    orderAmount,
                    billAmount,
                    unpaidAmount,
                    refundAmount,
                    couponAmount,
                    bonusAmount,
                    completeTime,
                    billUpdatedTime,
                    billState,
                    issue,
                    stops,
                    fees,
                    List.of(),
                    List.of());
        }
    }

    public record Stop(String name, String address, String contactName, String contactPhone) {}

    public record Fee(
            String code, String name, BigDecimal amount, String lineId, List<Voucher> vouchers) {
        public Fee(String code, String name, BigDecimal amount) {
            this(code, name, amount, null, List.of());
        }
    }

    /** 货拉拉回单业务信息；寄回快递单号不等同于本次运输的运单号。 */
    public record ReceiptInfo(
            Integer type,
            Integer status,
            Integer backType,
            String expressNo,
            String remark,
            Integer unableToReturn,
            String unableReason) {}

    public record StopPhotos(int stopIndex, String name, String phase, List<Voucher> vouchers) {}

    public record Voucher(
            String id,
            String name,
            String fileKey,
            String contentType,
            String state,
            Instant savedAt) {}

    public record VoucherUpdate(
            String connectorId,
            String enterpriseId,
            String orderNo,
            String lineId,
            Voucher voucher) {}

    /** 员工映射按版本投递；只更新平台下单人关联，不参与费用或销售单归属。 */
    public record EmployeeMappingUpdate(
            String connectorId,
            String enterpriseId,
            String userId,
            String employeeCode,
            long revision) {}

    public record ApiSource(String connectorId, String payloadHash, CarrierOrder order) {}

    public record Upload(String fileName, String fileHash, List<CarrierOrder> rows) {}

    public record Row(
            String id,
            int revision,
            String state,
            String costId,
            String fileName,
            CarrierOrder order,
            String sourceChannel,
            boolean sourceDifference,
            CarrierOrder latestOrder,
            Instant lastSyncedAt,
            String syncOutcome,
            BillVerification verification) {
        public Row(
                String id,
                int revision,
                String state,
                String costId,
                String fileName,
                CarrierOrder order) {
            this(
                    id, revision, state, costId, fileName, order, "EXCEL", false, null, null, null,
                    null);
        }

        public Row withSyncOutcome(String outcome) {
            return new Row(
                    id,
                    revision,
                    state,
                    costId,
                    fileName,
                    order,
                    sourceChannel,
                    sourceDifference,
                    latestOrder,
                    lastSyncedAt,
                    outcome,
                    verification);
        }
    }

    public record SourceQuery(int begin, String orderNo, String state, boolean differencesOnly) {}

    public record AcceptSource(int revision, String reason) {}

    public record BillVerification(
            BigDecimal amount, String reason, String actor, Instant verifiedAt) {}

    public record VerifyBill(int revision, BigDecimal amount, String reason) {}

    public record Generated(int revision, LogisticsCostCommand command) {}
}
