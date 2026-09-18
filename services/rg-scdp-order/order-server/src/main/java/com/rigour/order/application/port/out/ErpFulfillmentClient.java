package com.rigour.order.application.port.out;

import com.rigour.shared.context.CallerIdentity;

import java.time.Instant;
import java.util.Optional;

/** ERP 执行结果查询是只读，专用恢复身份不能触发新库存动作。 */
public interface ErpFulfillmentClient {
    record Receipt(
            String executionId,
            long orderId,
            long warehouseId,
            long stockOutId,
            String stockOutNo,
            Instant stockOutTime) {}

    Receipt execute(CallerIdentity actor, String executionId);

    Optional<Receipt> receipt(String tenant, String executionId);

    void requireWarehouse(String tenant, long warehouseId);

    java.util.List<com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption> warehouses(
            String tenant);
}
