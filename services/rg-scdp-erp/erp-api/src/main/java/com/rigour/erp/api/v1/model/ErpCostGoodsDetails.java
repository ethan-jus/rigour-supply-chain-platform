package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** 人工货品台账快照；不修改库存或采购单。 */
public record ErpCostGoodsDetails(
        Long productId,
        Long variantId,
        String productName,
        String specification,
        String unit,
        BigDecimal unitPrice,
        BigDecimal quantity,
        Long supplierId,
        String supplierName,
        Long warehouseId,
        String location,
        Long purchaseOrderId,
        String lossReason,
        String amountReason,
        ErpCostExternalDocument externalDocument) {}
