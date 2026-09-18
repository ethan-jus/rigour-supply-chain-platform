package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 供应链 BI 库存补货观察项。 */
public record SupplyDashboardInventoryReplenishmentItemView(
        String categoryCode,
        String categoryName,
        String productCode,
        String productName,
        String unitCode,
        BigDecimal salesQuantity,
        BigDecimal dailySalesQuantity,
        BigDecimal availableQuantity,
        BigDecimal inTransitQuantity,
        BigDecimal coverageDays,
        BigDecimal suggestedProcurementQuantity,
        String riskLevel,
        String inventoryStatus) {
}
