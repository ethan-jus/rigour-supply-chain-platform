package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 调拨单明细视图；商品名称、编码和单位为创建调拨单时快照。 */
public record InternalTransferOrderLineView(
        Long id,
        Integer lineNo,
        Long productId,
        Long productVariantId,
        String productCode,
        String variantCode,
        String productName,
        String unitCode,
        BigDecimal quantity,
        String remark) {
}
