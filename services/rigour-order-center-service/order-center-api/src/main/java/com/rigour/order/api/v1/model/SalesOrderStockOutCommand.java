package com.rigour.order.api.v1.model;

import java.time.Instant;

/**
 * 销售订单一键出库确认命令。
 *
 * <p>库存扣减和出库单生成由 ERP 完成；Order 只在 ERP 成功返回后更新销售订单出库状态。</p>
 */
public record SalesOrderStockOutCommand(
        Long warehouseId,
        Instant stockOutTime,
        String remark,
        Integer revision) {
}
