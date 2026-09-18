package com.rigour.order.api.v1.model;

import java.time.Instant;

/** 已选仓及持久化履约结果，前端不得把请求成功与真实扣库混为一谈。 */
public record OrderFulfillmentStatusView(
        long orderId,
        int orderRevision,
        Long warehouseId,
        String executionId,
        String status,
        Long stockOutId,
        String stockOutNo,
        Instant stockOutTime,
        String lastError) {}
