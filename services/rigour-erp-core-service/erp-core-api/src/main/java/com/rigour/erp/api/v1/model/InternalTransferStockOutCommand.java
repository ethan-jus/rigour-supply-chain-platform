package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 调拨出库确认命令；确认后生成调拨出库单并扣减来源仓库存。 */
public record InternalTransferStockOutCommand(
        Integer revision,
        Instant stockOutTime,
        String remark) {
}
