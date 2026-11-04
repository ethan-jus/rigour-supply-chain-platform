package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** ERP 采购订单保存/提交命令；采购单号由后端统一生成。 */
public record InternalProcurementOrderCommand(
        Boolean submit,
        Long supplierId,
        Long targetWarehouseId,
        Instant expectedArrivalTime,
        List<InternalProcurementOrderLineCommand> lines,
        String remark,
        Integer revision,
        String supplierCode,
        String targetWarehouseCode,
        LocalDate purchaseDate,
        BigDecimal freightAmount,
        String freightBearerCode,
        String contractNo,
        String contractFileKey,
        String receiverName,
        String receiverPhone,
        String receivingAddress) {
    public InternalProcurementOrderCommand(
            Boolean submit,
            Long supplierId,
            Long targetWarehouseId,
            Instant expectedArrivalTime,
            List<InternalProcurementOrderLineCommand> lines,
            String remark,
            Integer revision) {
        this(
                submit,
                supplierId,
                targetWarehouseId,
                expectedArrivalTime,
                lines,
                remark,
                revision,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public InternalProcurementOrderCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
