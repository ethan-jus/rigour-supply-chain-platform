package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 门店历史订单组、来源接入与回款接续契约；金额来自业务单据，不由门店名称猜测。 */
public final class HistorySyncModels {
    private HistorySyncModels() {}

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

    public record Baseline(long orderId, int revision, Instant cutoff, BigDecimal openingPaid) {}

    public record Bind(
            long customerId, List<SourceRef> sources, List<Baseline> orders, String evidence) {
        public Bind {
            sources = sources == null ? List.of() : List.copyOf(sources);
            orders = orders == null ? List.of() : List.copyOf(orders);
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
            String checksum) {}

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
