package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** ERP 采购明细；名称查询主档，履约数量查询有效入库记录，行号只用于旧接口兼容。 */
public record InternalProcurementOrderLineView(
        Long id,
        Integer lineNo,
        Long productId,
        Long productVariantId,
        String productCode,
        String variantCode,
        String productName,
        String unitCode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineAmount,
        BigDecimal receivedQuantity,
        String remark,
        String baseUnitCode,
        BigDecimal conversionRate,
        String specification,
        Boolean receiptProgressVerified,
        BigDecimal receivedBaseQuantity) {
    public InternalProcurementOrderLineView(
            Long id,
            Integer lineNo,
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineAmount,
            BigDecimal receivedQuantity,
            String remark) {
        this(
                id,
                lineNo,
                productId,
                productVariantId,
                productCode,
                variantCode,
                productName,
                unitCode,
                quantity,
                unitPrice,
                lineAmount,
                receivedQuantity,
                remark,
                null,
                null,
                null,
                true,
                receivedQuantity);
    }
}
