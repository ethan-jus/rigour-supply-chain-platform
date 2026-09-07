package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 外部来源调拨主单投影命令；只创建或更新我方调拨单，不确认出入库、不改库存余额。 */
public record ExternalTransferOrderProjectionCommand(
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
        Long sourceWarehouseId,
        Long targetWarehouseId,
        Instant sourceCreatedAt,
        String sourceTransferStatus,
        String sourceReviewStatus,
        String outboundOperatorStaffCode,
        String outboundOperatorStaffName,
        String inboundOperatorStaffCode,
        String inboundOperatorStaffName,
        List<ExternalTransferOrderProjectionLineCommand> lines,
        String remark) {
    public ExternalTransferOrderProjectionCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
