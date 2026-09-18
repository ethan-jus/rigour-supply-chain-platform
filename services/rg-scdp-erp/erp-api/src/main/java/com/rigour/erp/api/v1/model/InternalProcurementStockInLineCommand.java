package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 采购入库明细命令；一行对应一条采购订单明细。 */
public record InternalProcurementStockInLineCommand(
        Long procurementOrderLineId,
        BigDecimal quantity,
        String remark) {
}
