package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 履约最小快照：无价格或业绩信息；ERP 只执行 Order 已锁定的单据与仓库。 */
public record FulfillmentExecutionView(
        String tenantId,
        String executionId,
        long orderId,
        String orderNo,
        int orderRevision,
        long warehouseId,
        Long customerId,
        String customerName,
        Instant stockOutTime,
        List<Line> lines,
        String remark,
        String requestHash) {
    public FulfillmentExecutionView {
        lines = List.copyOf(lines);
    }

    public record Line(
            long orderLineId,
            long productId,
            long variantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal quantity,
            String remark) {}
}
