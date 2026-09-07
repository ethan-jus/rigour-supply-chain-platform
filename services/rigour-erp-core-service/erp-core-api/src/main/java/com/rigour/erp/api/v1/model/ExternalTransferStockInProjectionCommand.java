package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 外部来源调拨入库确认；sourceDocumentNo 是入库凭证号，transferSourceDocumentNo 是来源调拨主单号。 */
public record ExternalTransferStockInProjectionCommand(
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
        String transferSourceDocumentNo,
        Long transferOrderId,
        Instant stockInTime,
        String remark) {
}
