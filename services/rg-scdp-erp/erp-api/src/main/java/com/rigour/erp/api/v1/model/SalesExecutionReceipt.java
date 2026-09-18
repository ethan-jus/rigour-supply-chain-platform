package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** 与库存流水在同一 ERP 事务保存的执行回执。 */
public record SalesExecutionReceipt(
        String executionId,
        long orderId,
        long warehouseId,
        long stockOutId,
        String stockOutNo,
        Instant stockOutTime) {}
