package com.rigour.order.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 销售发货单保存命令；发货单号由后端按 Order 编码规则生成。 */
public record SalesShipmentCommand(
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
        Long salesOrderId,
        Long warehouseId,
        Long stockOutOrderId,
        String stockOutNo,
        String shipmentStatusCode,
        String logisticsCompany,
        String trackingNo,
        Instant shipTime,
        List<SalesShipmentLineCommand> lines,
        String remark,
        Integer revision) {
    public SalesShipmentCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public SalesShipmentCommand(
            Long salesOrderId,
            Long warehouseId,
            Long stockOutOrderId,
            String stockOutNo,
            String shipmentStatusCode,
            String logisticsCompany,
            String trackingNo,
            Instant shipTime,
            List<SalesShipmentLineCommand> lines,
            String remark,
            Integer revision) {
        this(null, null, null, salesOrderId, warehouseId, stockOutOrderId, stockOutNo,
                shipmentStatusCode, logisticsCompany, trackingNo, shipTime, lines, remark, revision);
    }
}
