package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 入库单明细视图；商品编码、名称优先来自入库明细快照，其次来自来源单据快照。 */
public record InternalStockInOrderLineView(
        Long id,
        Integer lineNo,
        Long procurementOrderLineId,
        Long transferOrderLineId,
        Long productId,
        Long productVariantId,
        String productCode,
        String variantCode,
        String productName,
        String unitCode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        String remark) {
}
