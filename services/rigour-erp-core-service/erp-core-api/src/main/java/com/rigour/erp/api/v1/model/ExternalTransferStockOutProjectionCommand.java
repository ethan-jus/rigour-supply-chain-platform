package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 外部来源调拨出库投影命令；用于订货宝只提供调拨出库、未提供调拨单接口的场景。 */
public record ExternalTransferStockOutProjectionCommand(
        UUID connectorId,
        String sourceSystemCode,
        /** 调拨出库凭证来源单号，例如订货宝 FH...。 */
        String sourceDocumentNo,
        /** 调拨主单来源单号，例如订货宝 DB...；为空时按旧逻辑从出库凭证反推。 */
        String transferSourceDocumentNo,
        /** 历史数据已按 FH 反推本地调拨单时的兜底调拨单 ID，常规新同步为空。 */
        Long transferOrderId,
        Long sourceWarehouseId,
        Long targetWarehouseId,
        Instant stockOutTime,
        Boolean affectStockBalance,
        String outboundOperatorStaffCode,
        String outboundOperatorStaffName,
        String inboundOperatorStaffCode,
        String inboundOperatorStaffName,
        List<ExternalTransferStockOutProjectionLineCommand> lines,
        String remark) {
    public ExternalTransferStockOutProjectionCommand {
        affectStockBalance = affectStockBalance == null ? Boolean.TRUE : affectStockBalance;
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public ExternalTransferStockOutProjectionCommand(
            UUID connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            String transferSourceDocumentNo,
            Long sourceWarehouseId,
            Long targetWarehouseId,
            Instant stockOutTime,
            Boolean affectStockBalance,
            String outboundOperatorStaffCode,
            String outboundOperatorStaffName,
            String inboundOperatorStaffCode,
            String inboundOperatorStaffName,
            List<ExternalTransferStockOutProjectionLineCommand> lines,
            String remark) {
        this(connectorId, sourceSystemCode, sourceDocumentNo, transferSourceDocumentNo, null,
                sourceWarehouseId, targetWarehouseId,
                stockOutTime, affectStockBalance, outboundOperatorStaffCode, outboundOperatorStaffName,
                inboundOperatorStaffCode, inboundOperatorStaffName, lines, remark);
    }

    public ExternalTransferStockOutProjectionCommand(
            UUID connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            Long sourceWarehouseId,
            Long targetWarehouseId,
            Instant stockOutTime,
            Boolean affectStockBalance,
            String outboundOperatorStaffCode,
            String outboundOperatorStaffName,
            String inboundOperatorStaffCode,
            String inboundOperatorStaffName,
            List<ExternalTransferStockOutProjectionLineCommand> lines,
            String remark) {
        this(connectorId, sourceSystemCode, sourceDocumentNo, null, null,
                sourceWarehouseId, targetWarehouseId, stockOutTime, affectStockBalance,
                outboundOperatorStaffCode, outboundOperatorStaffName, inboundOperatorStaffCode,
                inboundOperatorStaffName, lines, remark);
    }

    public ExternalTransferStockOutProjectionCommand(
            UUID connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            Long sourceWarehouseId,
            Long targetWarehouseId,
            Instant stockOutTime,
            String outboundOperatorStaffCode,
            String outboundOperatorStaffName,
            String inboundOperatorStaffCode,
            String inboundOperatorStaffName,
            List<ExternalTransferStockOutProjectionLineCommand> lines,
            String remark) {
        this(connectorId, sourceSystemCode, sourceDocumentNo, null, null, sourceWarehouseId, targetWarehouseId,
                stockOutTime, Boolean.TRUE, outboundOperatorStaffCode, outboundOperatorStaffName,
                inboundOperatorStaffCode, inboundOperatorStaffName, lines, remark);
    }
}
