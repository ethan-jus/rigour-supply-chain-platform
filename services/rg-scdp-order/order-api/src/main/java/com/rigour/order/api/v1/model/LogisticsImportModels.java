package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 货拉拉原始报表独立暂存；只有明确关联销售单后才生成待确认费用。 */
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
            List<Fee> fees) {}

    public record Stop(String name, String address, String contactName, String contactPhone) {}

    public record Fee(String code, String name, BigDecimal amount) {}

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
