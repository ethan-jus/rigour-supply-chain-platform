package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 一张已确认采购单对应一条汇总；实际运费单独核实，约定金额不是实际成本。 */
public record ProcurementCostRecordView(
        long orderId,
        String procurementNo,
        String sourceSystemCode,
        String sourceDocumentNo,
        int orderRevision,
        String supplierName,
        String warehouseName,
        LocalDate purchaseDate,
        BigDecimal goodsAmount,
        GoodsSourceCheck goodsSource,
        /** 采购单原约定运费，承担方单列；不得当作已核实的我方运费。 */
        BigDecimal agreedFreightAmount,
        String freightBearerCode,
        BigDecimal actualFreightAmount,
        String freightRecordId,
        Integer freightRevision,
        String freightRecordStatus,
        String freightStatus,
        BigDecimal confirmedAmount,
        List<Line> lines) {
    public record GoodsSourceCheck(String issue, BigDecimal orderAmount, BigDecimal linesAmount) {}

    public record Line(
            String productName,
            String variantCode,
            String unitCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount) {}

    public ProcurementCostRecordView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
