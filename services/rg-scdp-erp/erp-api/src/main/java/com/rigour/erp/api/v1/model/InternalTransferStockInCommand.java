package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 调拨入库确认命令；确认后生成统一入库单并增加目标仓库存。 */
public record InternalTransferStockInCommand(
        Integer revision,
        Instant stockInTime,
        String remark) {
}
