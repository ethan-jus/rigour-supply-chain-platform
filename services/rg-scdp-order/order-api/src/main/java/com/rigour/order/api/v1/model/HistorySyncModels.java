package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 门店历史订单组、来源接入与回款接续契约；金额来自业务单据，不由门店名称猜测。 */
public final class HistorySyncModels {
    public record VoucherBackfillClaim(java.util.UUID connectorId, int limit, java.time.Instant scanStartedAt) {}
    public record VoucherReceiptReference(String receiptNo, String sourceOrderNo) {}
    public record VoucherBackfillCandidate(long paymentId, String leaseToken, java.util.List<VoucherReceiptReference> receipts) {}
    public record VoucherBackfillResult(java.util.UUID connectorId, long paymentId, String leaseToken, String state) {}


    private HistorySyncModels() {}

    /** 仅更新已关联回款的自有存储附件，不修改金额、付款日期或确认状态。 */
    public record ReceiptVouchers(UUID connectorId, String receiptNo, String sourcePaymentNo,
                                  String sourceOrderNo, String sourceStatus, java.util.List<String> objectKeys) {}

    public record CancelSourceOrder(UUID connectorId, String sourceNo, String orderStatusCode,
                                    Instant orderDate, BigDecimal amount, String customerName,
                                    Instant sourceUpdatedAt, CancelledOrderAttribution attribution) {
        public CancelSourceOrder(UUID connectorId, String sourceNo, String orderStatusCode,
                                 Instant orderDate, BigDecimal amount, String customerName, Instant sourceUpdatedAt) {
            this(connectorId, sourceNo, orderStatusCode, orderDate, amount, customerName, sourceUpdatedAt, null);
        }
        public CancelSourceOrder(UUID connectorId, String sourceNo) {
            this(connectorId, sourceNo, "CANCELLED", null, null, null, null);
        }
    }

    /** 取消不依赖商品映射；来源仍可读取时保留已知的客户、人员和地区。 */
    public record CancelledOrderAttribution(Long customerId, String customerCode, String regionCode,
            String ownerEmployeeCode, String ownerEmployeeName, String sourceCreatorId,
            String sourceCreatorCode, String sourceCreatorName) {}

    public record SourceOrder(
            UUID connectorId,
            String sourceNo,
            SalesOrderCommand order,
            BigDecimal amount,
            String checksum) {}

    public record Intake(
            String state, String groupId, String reason, String employeeCode, String employeeName) {
        public Intake(String state, String groupId, String reason) {
            this(state, groupId, reason, null, null);
        }
    }

    public record SourceRef(UUID connectorId, String sourceNo, int revision) {}

    public record NewOrder(SourceRef source, String evidence) {}

    public record DeleteUnlinkedHistory(long orderId, int revision, String evidence) {}

    public record HistoryOrderRef(long orderId, int revision) {}

    public record SourceTarget(SourceRef source, Long orderId, SalesOrderCommand command, SourceOrder sourceOrder) {}

    public record PaymentPortion(String sourceNo, BigDecimal amount) {}

    public record PaymentSplit(long paymentId, int revision, List<PaymentPortion> portions) {}

    public record NormalizeGroup(UUID operationId, UUID connectorId, String groupId,
                                 List<HistoryOrderRef> orders, List<SourceTarget> targets,
                                 List<PaymentSplit> payments, String evidence) {}

    public record Baseline(long orderId, int revision, Instant cutoff, BigDecimal openingPaid) {}

    public record Bind(
            long customerId, List<SourceRef> sources, List<Baseline> orders, String evidence,
            String differenceReason, Boolean alignOrderAmount) {
        public Bind {
            sources = sources == null ? List.of() : List.copyOf(sources);
            orders = orders == null ? List.of() : List.copyOf(orders);
        }

        public Bind(
                long customerId, List<SourceRef> sources, List<Baseline> orders, String evidence) {
            this(customerId, sources, orders, evidence, null, null);
        }

        public Bind(
                long customerId, List<SourceRef> sources, List<Baseline> orders, String evidence,
                String differenceReason) {
            this(customerId, sources, orders, evidence, differenceReason, null);
        }
    }

    public record Receipt(
            UUID connectorId,
            String receiptNo,
            String sourceOrderNo,
            Long customerId,
            BigDecimal amount,
            Instant occurredAt,
            String status,
            Instant updatedAt,
            String checksum, String confirmedBy, Instant confirmedAt) {
        public Receipt(UUID connectorId,String receiptNo,String sourceOrderNo,Long customerId,BigDecimal amount,
                Instant occurredAt,String status,Instant updatedAt,String checksum) {
            this(connectorId,receiptNo,sourceOrderNo,customerId,amount,occurredAt,status,updatedAt,checksum,null,null);
        }
    }

    public record Allocation(long orderId, BigDecimal amount) {}

    public record Allocate(
            UUID connectorId,
            String receiptNo,
            int revision,
            List<Allocation> allocations,
            String evidence) {
        public Allocate {
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
        }
    }

    public record ProductAllocation(long orderId, long lineId, BigDecimal amount) {}

    public record AllocateProducts(
            UUID connectorId,
            String receiptNo,
            int revision,
            List<ProductAllocation> allocations,
            String evidence) {
        public AllocateProducts {
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
        }
    }

    public record Performance(
            List<Map<String, Object>> sales,
            List<Map<String, Object>> receipts,
            List<Map<String, Object>> products,
            List<Map<String, Object>> productReceipts,
            Map<String, Object> pending) {}

    public record OwnerReview(
            UUID connectorId,
            String receiptNo,
            int revision,
            String employeeCode,
            String employeeName,
            String evidence) {}

    public record StoreView(
            List<Map<String, Object>> stores,
            List<Map<String, Object>> sourceOrders,
            List<Map<String, Object>> historyOrders,
            List<Map<String, Object>> groups,
            List<Map<String, Object>> receipts) {}
}
