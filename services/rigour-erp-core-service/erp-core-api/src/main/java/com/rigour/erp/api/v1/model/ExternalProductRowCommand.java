package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 外部商品单行同步命令。 */
public record ExternalProductRowCommand(
        UUID connectorId,
        String sourceTenantKey,
        String sourceProductId,
        String sourceDocumentNo,
        String productName,
        String businessLineName,
        String brandName,
        String industryName,
        String categoryName,
        String specification,
        String unitCode,
        BigDecimal salePrice,
        BigDecimal marketPrice,
        BigDecimal purchasePrice,
        String statusName,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        String sourcePayloadHash,
        String sourcePayloadJson) {
}
