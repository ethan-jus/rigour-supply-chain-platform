package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** BI 采购/发货/库存品项汇总。 */
public record SupplyDashboardInventoryItemSummaryView(
        String categoryCode,
        String categoryName,
        String unitCode,
        BigDecimal procurementQuantity,
        BigDecimal shippedQuantity,
        BigDecimal remainingQuantity,
        BigDecimal inactiveRemainingQuantity) {
}
