package com.rigour.erp.api.v1.model;

import java.util.List;

/** ERP 调拨单保存命令；调拨单保存不直接扣减库存。 */
public record InternalTransferOrderCommand(
        Long sourceWarehouseId,
        Long targetWarehouseId,
        List<InternalTransferOrderLineCommand> lines,
        String remark,
        Integer revision) {
    public InternalTransferOrderCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
